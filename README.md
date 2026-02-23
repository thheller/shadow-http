# shadow-http

This is a vibe coded HTTP Server with the goal of using it in shadow-cljs. It isn't yet, but might be in the future.

## Rationale

I want a simpler HTTP server than undertow. shadow-cljs uses like 5% of its features. I looked at alternatives, but Jetty is equally bloated. http-kit would have worked, but is too ring specific for my tastes. Also, kinda hate its websocket implementation. I also didn't like much of the non-ring APIs.

Pretty much every available Java HTTP Server is built using async IO. I wanted something simpler. So, this is using entirely boring old blocking java.io Streams and each connection gets its own virtual thread.

The goal here is to create a simple HTTP server that can serve everything shadow-cljs needs (simple UI requests, websockets, static files). It is not a goal to make this a production grade server. It'll also never support Servlets or any Java Enterprise nonsense.

## Status

Definitely not usable yet, but getting there, hopefully.


I vibe coded this over the weekend. Started with pi-coding-agent and Claude Opus 4.6, but the agent was getting rather annoying and was just rewriting good code for no reason. Switched to manual GitHub Copilot prompts, which isn't much better, but gets the job done for simple tasks. Easier to try different models too. Lots of the code I still just wrote myself. No Skills or further Markdown docs were used to write this, which is probably a mistake. Basically just fed it the HTTP/1.1 and WebSocket RFC. First time giving AI a shot, so no clue how this works.

Preliminary benchmarks look promising.

Ran a dummy benchmark serving docs/rfc9112.txt with on-the-fly gzip compression and chunked encoding.

```
wrk -t8 -c200 -d10s http://localhost:5007/rfc9112.txt

Running 10s test @ http://localhost:5007/rfc9112.txt
  8 threads and 200 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     3.54ms    1.44ms  50.99ms   73.98%
    Req/Sec     5.50k   442.91     6.85k    78.25%
  440059 requests in 10.06s, 45.14GB read
Requests/sec:  43726.31
Transfer/sec:      4.49GB
```

Ran the same thing to shadow-cljs (using shadow-undertow) and got

```
wrk -t8 -c200 -d10s http://localhost:8605/rfc9112.txt

Running 10s test @ http://localhost:8605/rfc9112.txt
  8 threads and 200 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    25.20ms   45.33ms 508.13ms   87.76%
    Req/Sec     4.91k     1.09k    8.87k    70.56%
  393237 requests in 10.10s, 40.35GB read
Requests/sec:  38922.56
Transfer/sec:      3.99GB
```

So, it is a decent start. For both most time is spent in syscalls, so probably not the best benchmark overall.

Started this as an experiment mostly. Wanted to give AI a shot and this seemed like something it might be able to write. It isn't, but getting close sometimes. Also wanted to see how virtual threads hold up. I never actually expected to write this, but here we are.