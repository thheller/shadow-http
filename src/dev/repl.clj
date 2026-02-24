(ns repl
  (:require
    ;; optional, remove if you are going to use any kind of JVM logging
    [shadow.cljs.silence-default-loggers]
    [build]
    [clojure.main :as main]
    [shadow.user]
    [shadow.cljs.devtools.server :as shadow-server]
    [shadow.http.server :as server]
    [ring.websocket :as ring-ws])
  (:import [shadow.http.server FileHandler HttpHandler Server]))

(defn start []
  (shadow-server/start!)

  ::started)

(defn stop []
  ::stopped)

(defn go []
  (stop)
  (start))

(defn repl-init []
  (in-ns 'shadow.user))

(defn -main [& args]
  (start)
  (main/repl :init repl-init))


(comment
  (def server (Server.))

  (def files (-> (FileHandler/forPath "docs")
                 (.findFiles)))

  (def ring (server/ring-handler
              (fn [req]
                (if (= "/ws" (:uri req))
                  {::ring-ws/listener
                   {:on-open
                    (fn [socket]
                      (prn [:on-open socket])
                      (ws/send socket "hello world"))

                    :on-message
                    (fn [socket message]
                      (prn [:on-message message])
                      (ws/send socket message))

                    :on-close
                    (fn [socket status reason]
                      (prn [:on-close status reason]))}}
                  {:status 200
                   :body "Hello World"}))))

  (.setHandlers server (into-array HttpHandler [files ring]))
  (.start server 5008)

  (prn server)

  (.stop server))
