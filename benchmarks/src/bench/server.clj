(ns bench.server
  (:require
    [bench.handlers :as handlers]
    [org.httpkit.server :as httpkit]
    [ring.adapter.jetty :as jetty]
    [shadow.http.server :as shadow-http])
  (:import
    [org.eclipse.jetty.server Server]))

(set! *warn-on-reflection* true)

(defn start-server [server-name {:keys [host port jetty-max-threads]
                                 :or {host "127.0.0.1"
                                      port 18080
                                      jetty-max-threads 200}}]
  (case server-name
    "shadow-http"
    (let [svc (shadow-http/start {:host host :port port}
                (shadow-http/ring-handler handlers/ring-handler))]
      {:name server-name
       :host host
       :port (:port svc)
       :stop #(shadow-http/stop svc)})

    "shadow-http-native"
    (let [svc (shadow-http/start {:host host :port port}
                (handlers/shadow-native-handler))]
      {:name server-name
       :host host
       :port (:port svc)
       :stop #(shadow-http/stop svc)})

    "jetty"
    (let [^Server srv (jetty/run-jetty handlers/ring-handler
                        {:host host
                         :port port
                         :join? false
                         :max-threads jetty-max-threads})]
      {:name server-name
       :host host
       :port port
       :stop #(.stop srv)})

    "http-kit"
    (let [srv (httpkit/run-server handlers/httpkit-handler
                {:ip host
                 :port port
                 :legacy-return-value? false})]
      {:name server-name
       :host host
       :port (httpkit/server-port srv)
       :stop #(httpkit/server-stop! srv {:timeout 500})})

    (throw (ex-info (str "Unknown server: " server-name
                         " (expected: shadow-http, shadow-http-native, jetty, http-kit)")
                    {:server server-name}))))

(defn- parse-args [args]
  (loop [[a & more] args
         opts {:host "127.0.0.1" :port 18080 :jetty-max-threads 200}]
    (cond
      (nil? a) opts
      (= a "--host") (recur (rest more) (assoc opts :host (first more)))
      (= a "--port") (recur (rest more) (assoc opts :port (parse-long (first more))))
      (= a "--jetty-max-threads") (recur (rest more) (assoc opts :jetty-max-threads (parse-long (first more))))
      (not (.startsWith ^String a "--")) (recur more (assoc opts :server a))
      :else (throw (ex-info (str "Unknown option: " a) {:arg a})))))

(defn -main [& args]
  (let [{:keys [server] :as opts} (parse-args args)]
    (when-not server
      (binding [*out* *err*]
        (println "Usage: clojure -M:server <shadow-http|shadow-http-native|jetty|http-kit> [--host 127.0.0.1] [--port 18080]"))
      (System/exit 1))
    (let [{:keys [host port stop]} (start-server server opts)
          latch (java.util.concurrent.CountDownLatch. 1)]
      (.addShutdownHook (Runtime/getRuntime)
        (Thread. ^Runnable (fn []
                             (try (stop) (catch Throwable _))
                             (.countDown latch))))
      (println (format "READY %s %s:%d" server host port))
      (flush)
      (.await latch))))
