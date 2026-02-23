(defproject com.thheller/shadow-http "0.1.0"
  :description "HTTP Server for shadow-cljs"
  :url "https://github.com/thheller/shadow-http"

  :license
  {:name "Eclipse Public License"
   :url "http://www.eclipse.org/legal/epl-v10.html"}

  :repositories
  {"clojars" {:url "https://clojars.org/repo"
              :sign-releases false}}

  :dependencies
  [[org.clojure/clojure "1.12.1" :scope "provided"]]

  :source-paths
  ["src/main"]

  :javac-options
  ["--release" "21"]

  :java-source-paths
  ["src/java"]

  :profiles
  {:dev
   {:source-paths
    ["src/dev"]

    :java-source-paths
    ["src/java"
     "src/test"]

    :dependencies
    [[org.junit.jupiter/junit-jupiter-api "5.9.2"]
     [org.junit.jupiter/junit-jupiter-engine "5.9.2"]]
    }})
