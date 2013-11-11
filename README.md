> Phil: I have been stabbed, shot, poisoned, frozen, hung, electrocuted, and burned.

> Rita: Oh, really?

> Phil: ...and every morning I wake up without a scratch on me, not a dent in the fender... I am an immortal.


groundhog
=========

A clojure ring request recorder and replayer.

How To Groundhog
================
In your `project.clj`:

    :dependencies [...
                   ...
                   [noisesmith/groundhog "0.0.3"]]

In your ring handler:

    (ns app.ns
      (:require ...
                [noisesmith.groundhog :as groundhog]))

    (def handler
      (-> default/handler
          ...
          groundhog/groundhog))

By putting the groundhog handler last, you let it see the request before any
other middleware.

The middleware takes an optional opts map:

    {:sanitize sanitize-fn
     :store store-fn
     :replay replay-fn}

Each of these has a default implementation, but if you want to store anywhere other than a single global atom, or remove any sensitive data from the request body, you will likely want to provide custom implementations of each function.

store and replay should take and return a serialized request map, respectively. The map will be vanilla clojure edn, (unless some other handler has put something else in the request before groundhog sees it).

    (fn store [rq] (spit (make-name rq) (pr-str rq)))

    (fn replay [rq] (when (is-replay-request rq)
                       (slurp (make-find-name rq))))

Sanitize should take a and return a request map, and probably wants to check and / or manipulate the :body-bytes key which holds the copied bytes from the request body stream.

    (fn sanitize [rq]
      (try
         (->> rq
              :body-bytes
              String.
              cheshire/read-string
              (walk/postwalk (fn [e]
                               (if (map? e)
                                 (dissoc e "password")
                                 e))
             cheshire/generate-string
             .getBytes
             (assoc rq :body-bytes %))
         (catch Exception e ;; not a json body?
                rq)))

 Since your body is likely text, the `make-transform-as-string` function is provided to use a sanitizer that takes and returns a String. Byte-array is the default because the body may not be 8 bit clean (depending on your client, and what they decide to try to send you).

## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)
