(ns shadow.http.server.ring
  (:require
    [shadow.http.server :as-alias srv]
    [ring.websocket.protocols :as ring-protocols])
  (:import
    [java.io InputStream File]
    [java.nio ByteBuffer]
    [shadow.http.server
     HttpHandler HttpRequest HttpResponse
     WebSocketConnection WebSocketHandler]))

(defn build-request-map
  "Builds a Ring request map from an HttpRequest."
  [^HttpRequest request]
  (let [target (.getRequestTarget request)
        qi (.indexOf target (int \?))
        uri (if (neg? qi) target (subs target 0 qi))
        query-string (when (pos? qi) (subs target (inc qi)))

        ;; Ring requires lowercased header names -> values
        ;; HttpRequest.headers already stores name lowercased -> value
        headers (.getRequestHeaders request)

        ;; extract host header for server-name/server-port
        host (.get headers "host")
        [server-name server-port]
        (when host
          (let [ci (.lastIndexOf ^String host (int \:))]
            (if (neg? ci)
              [host 80]
              [(subs host 0 ci)
               (try (Integer/parseInt (subs host (inc ci)))
                    (catch Exception _ 80))])))

        method (keyword (.toLowerCase (.getRequestMethod request)))]

    (cond-> {:request-method method
             :uri uri
             :headers (into {} headers)
             :protocol (.getRequestVersion request)
             :scheme :http
             :server-name (or server-name "localhost")
             :server-port (or server-port 80)
             :remote-addr ""}
      query-string
      (assoc :query-string query-string)

      (.requestHasBody request)
      (assoc :body (.requestBody request))
      )))

(defn write-ring-response
  "Writes a Ring response map to the HttpResponse."
  [^HttpRequest request ring-response]
  (let [status (get ring-response :status 200)
        headers (get ring-response :headers)
        body (get ring-response :body)]

    (.setResponseStatus request status)

    ;; set all other headers
    (doseq [[k v] headers]
      (if (string? v)
        (.setResponseHeader request k v)
        ;; ring allows header values to be vectors of strings
        (throw (ex-info "currently not supporting header vector values" {:header k :value v}))))

    ;; write body
    (cond
      (nil? body)
      (.respondNoContent request)

      (string? body)
      (.writeString request ^String body)

      (instance? InputStream body)
      (.writeStream request ^InputStream body)

      (instance? (Class/forName "[B") body)
      (with-open [^java.io.OutputStream out (.requestBody request)]
        (.write out ^bytes body)
        (.flush out))

      (instance? File body)
      (with-open [is (java.io.FileInputStream. ^File body)]
        (.writeStream request is))

      (seq? body)
      (with-open [^java.io.OutputStream out (.requestBody request)]
        (doseq [chunk body]
          (when chunk
            (cond
              (string? chunk)
              (.write out (.getBytes ^String chunk "UTF-8"))
              (instance? (Class/forName "[B") chunk)
              (.write out ^bytes chunk))))
        (.flush out))

      :else
      (do (.writeString request (str body))))))

(deftype RingWebSocketHandler [^WebSocketConnection ctx listener]
  WebSocketHandler
  (start [_ ws-ctx]
    (let [next (RingWebSocketHandler. ws-ctx listener)
          next-listener (ring-protocols/on-open listener next)]
      (if (satisfies? ring-protocols/Listener next-listener)
        (RingWebSocketHandler. ws-ctx next-listener)
        next)))

  (onText [this payload]
    (ring-protocols/on-message listener this payload)
    this)

  (onBinary [this payload]
    (ring-protocols/on-message listener this (ByteBuffer/wrap payload))
    this)

  (onPing [this payload]
    (ring-protocols/on-ping listener this (ByteBuffer/wrap payload))
    this)

  (onPong [this payload]
    (ring-protocols/on-pong listener this (ByteBuffer/wrap payload))
    this)

  (onClose [this status-code reason]
    (ring-protocols/on-close listener this status-code reason))

  ring-protocols/Socket
  (-open? [this]
    (.isOpen ctx))

  (-send [this message]
    (if (string? message)
      (.sendText ctx message)
      (throw (ex-info "TBD" {})))
    this)

  (-ping [this data]
    (.sendPing ctx (.array ^ByteBuffer data))
    this)
  (-pong [this data]
    (.sendPong ctx (.array ^ByteBuffer data))
    this)

  (-close [this code reason]
    (.sendClose ctx code)
    this
    ))

(defn ring-ws-handler
  "Creates a WebSocketHandler from a ring.websocket listener map."
  [listener]
  (RingWebSocketHandler. nil listener))

(deftype RingHandler [handler-fn]
  HttpHandler
  (handle [this request]
    (let [ring-request (build-request-map request)
          ring-response (handler-fn ring-request)]

      (when ring-response
        ;; check for websocket upgrade response
        ;; prefer our own impl over ring impl if present
        (if-let [^WebSocketHandler handler (::srv/handler ring-response)]
          (.upgradeToWebSocket request handler (::srv/protocol ring-response))
          (if-let [listener (:ring.websocket/listener ring-response)]
            ;; websocket upgrade
            (let [ws-handler (ring-ws-handler listener)
                  sub-protocol (:ring.websocket/protocol ring-response)]
              (.upgradeToWebSocket request ws-handler sub-protocol))

            ;; normal HTTP response
            (write-ring-response request ring-response)))))))

(defn handler [handler-fn]
  (RingHandler. handler-fn))
