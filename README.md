# shadow-http

[![Clojars Project](https://img.shields.io/clojars/v/com.thheller/shadow-http.svg)](https://clojars.org/com.thheller/shadow-http)

This is a vibe coded HTTP Server for shadow-cljs.

Other Java webserver implementations are needlessly complex for the needs of shadow-cljs, as well a potential source of conflicts where projects will often have a webserver dependency themselves. They might not want the same version shadow-cljs would, so rather have something purpose built that has all the required features and nobody else uses.

Rough implementation started by Opus 4.6, then refactored almost everything manually.