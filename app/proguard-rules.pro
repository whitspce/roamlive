# StreamingEngine reaches GenericStreamClient's private srtClient field by
# reflection (RootEncoder doesn't expose the per-protocol clients publicly);
# the field name must survive shrinking or SRT latency/loss stats silently
# degrade. Everything else in the app and its libraries is reached directly,
# so R8's reachability analysis covers it.
-keepclassmembers class com.pedro.library.util.streamclient.GenericStreamClient {
    com.pedro.library.util.streamclient.SrtStreamClient srtClient;
}
