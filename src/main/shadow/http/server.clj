(ns shadow.http.server
  (:require
    [clojure.core.async :as async]
    [shadow.http.server.ring :as ring])
  (:import
    [java.util List]
    [shadow.http.server ClasspathHandler FileHandler HandlerList HttpHandler Server WebSocketHandler]))

(defmacro vthread [& body]
  `(let [res# (async/chan 1)]
     (-> (Thread/ofVirtual)
         (.start (fn* []
                   (try
                     (do ~@body)
                     (finally
                       (async/close! res#))))))
     res#))

(defn ring-handler [handler-fn]
  (ring/handler handler-fn))

(defn classpath-handler [prefix]
  (ClasspathHandler/forPrefix prefix))

(defn file-handler [path]
  (FileHandler/forPath path))

(deftype CoreAsyncWebSocketHandler [context write-loop ws-in ws-out ws-loop]
  WebSocketHandler
  (start [this new-context]
    (let [write-loop
          (vthread
            (loop []
              (when-some [msg (async/<!! ws-out)]
                (.sendText new-context msg)
                (recur))))]

      (CoreAsyncWebSocketHandler. new-context write-loop ws-in ws-out ws-loop)))

  (onText [this text]
    (async/>!! ws-in text))

  (onClose [this status reason]
    (async/close! ws-in)
    (async/close! ws-out)))

(defn ws-upgrade
  ([ws-in ws-out]
   (ws-upgrade ws-in ws-out nil))
  ([ws-in ws-out ws-loop]
   {::handler (CoreAsyncWebSocketHandler. nil nil ws-in ws-out ws-loop)}))

(defn start [{:keys [host port] :as config} handler]
  (let [server (Server.)]
    (cond
      (seq handler)
      (.setHandler server (HandlerList/create ^List handler))

      (instance? HttpHandler handler)
      (.setHandler server handler)

      :else
      (throw (ex-info "invalid handler" {:handler handler})))
    (.start server (or host "0.0.0.0") port)
    {:config config
     :server server
     :port (.getLocalPort (.getSocket server))}))

(defn stop [{:keys [^Server server] :as svc}]
  (.stop server)
  (dissoc svc ::server))