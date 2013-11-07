(ns noisesmith.groundhog
  (:require [clojure.java.io :as io]
            [clojure.data.codec.base64 :as b64])
  (:import (java.io ByteArrayInputStream
                    ByteArrayOutputStream)
           (java.util Date)))

(defonce requests (atom []))

(defn serialize
  "Make a version of the debug-request that can be transperently printed and
   read."
  [debug-request]
  (dissoc debug-request :body))

(defn default-sanitize
  "The non-sanitizing sanitize"
  [byte-array]
  byte-array)

(defn make-transform-as-string
  "A helper function for making sanitizers. Not guaranteed to be useful if the
   request body is not 8 bit clean."
  [f]
  (fn transform-as-string [byte-array]
    (-> byte-array
        (#(String. ^bytes %))
        f
        (#(.getBytes ^String %)))))

(defn default-store
  "Default implementation to store all requests for later replay"
  [serialized-request]
  (swap! requests conj (assoc serialized-request :time (Date.))))

(defn default-replay
  "Default implementation to steal requests and replace them with stored ones."
  [request]
  (let [{:keys [uri remote-addr]} request
        route-re #"/replay/([0-9])+"
        [_ match-num-str] (re-matches route-re uri)]
    (when (and (= remote-addr "127.0.0.1")
               match-num-str)
      (nth @requests (read-string match-num-str)))))


(defn deserialize
  "Recreates a request, complete with :body stream to read from, out of a
   serialized request"
  [serialized-request]
  (let [encoded (:body64 serialized-request)
        bytes (.getBytes ^String encoded)
        decoded (if (= encoded "")
                  (byte-array [])
                  (b64/decode bytes))
        stream (ByteArrayInputStream. decoded)]
    (assoc serialized-request :body stream)))

(defn tee-stream
  "Given a stream we can read from, returns the eagerly read bytes of the stream,
   plus a new stream that will provide those same contents."
  [stream]
  (let [buffer (ByteArrayOutputStream.)
        _ (io/copy stream buffer)
        bytes (.toByteArray buffer)]
    {:stream (ByteArrayInputStream. bytes)
     :contents bytes}))

(defn duplicate
  "Duplicates the request and stores a serialized version."
  [request sanitize store]
  (let [{body :stream
         contents :contents} (tee-stream (:body request))
         encoded-bytes (-> contents
                           sanitize
                           b64/encode)
         encoded (String. ^bytes encoded-bytes)
         duplicated-request (assoc request
                              :body body
                              :body64 encoded)]
    (future (store (serialize duplicated-request)))
    duplicated-request))

(defn groundhog
  "A ring middleware to record and then retrieve and optionally replay requests.
   Opts:
   :sanitize
      will get a byte-array and should return one, and has the opportunity
        to remove any data that may be considered sensitive before storage. We
        use byte array because the input may not be a string (though it usually
        will be).
      defaults to identity
   :store
      will get the serialized form of the request as an argument
      defaults to adding a timestamp and conjing on a global atom in this
        namespace.
   :replay
      a predicate on the request, if it returns a serialized request, that
        request will be replayed
      defaults to looking for /replay/<count> and retrieving from the
        request atom in this namespace"
  [handler & [{:keys [sanitize store replay]
               :or {sanitize default-sanitize
                    store default-store
                    replay default-replay}
               :as opts}]]
  (fn groundhog-handler [request]
    (if-let [stored (replay request)]
      ;; if replay returns truthy, we do not store the request, and we replay
      ;; the data that it hands back to us
      (handler (deserialize stored))
      ;; if replay returns falsey, we store the sanitized request for later,
      ;; and then pass the non-sanitized on to the handler as normal
      (handler (duplicate request sanitize store)))))
