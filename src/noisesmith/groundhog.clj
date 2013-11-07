(ns noisesmith.groundhog
  (:require [clojure.java.io :as io]
            [clojure.data.codec.base64 :as b64])
  (:import (java.io ByteArrayInputStream
                    ByteArrayOutputStream)))

(defonce requests (atom []))

(defn tee-stream
  [stream]
  (let [buffer (ByteArrayOutputStream.)
        _ (io/copy stream buffer)
        bytes (.toByteArray buffer)]
    {:stream (ByteArrayInputStream. bytes)
     :contents bytes}))

(defn serialize
  [debug-request]
  (dissoc debug-request :body))

(defn debugging
  "Sanitizer will get a byte-array and should return one, and has the opportunity
   to remove any data that may be considered sensitive before storage. We use
   byte array because the input may not be a string (though it usually will be)."
  [handler sanitizer]
  (fn [request]
    (let [{body :stream
           contents :contents} (tee-stream (:body request))
          encoded-bytes (-> contents
                      sanitizer
                      b64/encode)
          encoded (String. ^bytes encoded-bytes)
          backed-up-request (assoc request
                              :body body
                              :body64 encoded)]
      (swap! requests conj (serialize backed-up-request))
      (handler backed-up-request))))

(defn deserialize
  [serialized-request]
  (let [encoded (:body64 serialized-request)
        bytes (.getBytes ^String encoded)
        decoded (b64/decode bytes)
        stream (ByteArrayInputStream. decoded)]
    (assoc serialized-request :body stream)))

(defn replayer
  [handler sanitizer]
  (fn [serialized-request]
    (handler (deserialize serialized-request))))
