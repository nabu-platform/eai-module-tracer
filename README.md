# Hiding unmarshallable content

Trace mode sends back information about the runtime on the server, however it needs to serialize the data to do that. It uses JSON (in the past XML) to accomplish this.

However, some data can not be serialized at all (e.g. circular dependency datasets) or without impacting the runtime (e.g. a stream).
To this end the StreamHiderContent was set up, originally only meant to hide streams. Since then I've added the "MarshalRule" concept in types which can hide other things like excel workbooks etc.