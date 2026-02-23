(ns repl
  (:require
    ;; optional, remove if you are going to use any kind of JVM logging
    [shadow.cljs.silence-default-loggers]
    [build]
    [clojure.main :as main]
    [shadow.user]
    [shadow.cljs.devtools.server :as server]))

(defonce css-watch-ref (atom nil))

(defn start []
  (server/start!)

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