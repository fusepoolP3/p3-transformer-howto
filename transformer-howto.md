How to write a Java Fusepool P3 Transformer
===========================================

## Overview

A core feature of the
[Fusepool P3 platform](http://fusepool.gitbooks.io/the_fusepool_p3_platform/)
(or just P3, for short) is its ability to allow the definition of
_data transformation pipelines_ by the end user. Such transformation
pipelines allow data to be flexibly processed, in an automatic way,
through a chain of _transformers_ as part of them being loaded into
the platform.

[Transformers](https://github.com/fusepoolP3/overall-architecture/blob/master/data-transformer-api.md)---which
are simple, open-ended HTTP services---are the key components in this
model, and the extensibility of the platform relies on our ability to
write and deploy new ones. P3 currently provides two ways by which one
can write a new transformer:

1. by directly implementing the
   [transformer HTTP API](https://github.com/fusepoolP3/overall-architecture/blob/master/data-transformer-api.md);
2. by leveraging the
   [P3 transformer library](https://github.com/fusepoolP3/p3-transformer-library),
   which provides Java interface bindings and a runtime for Java-based
   transformers, and frees the developer from having to reason about
   API compliance.

This document is about the latter; namely, about how to write a
Java-based transformer by leveraging the P3 transformer library. 

## A Simple Transformer

To showcase how general P3 transformers can be, we will start with a
simple example of a transformer that takes: _i)_ a text document, and;
_ii)_ a stream editor ([`sed`](http://en.wikipedia.org/wiki/Sed))
script; and outputs the result, in plain text, of applying the script
to the input document. To access `sed` from Java, we will rely on the
[Unix4j libraries](https://code.google.com/p/unix4j/).

Most of the work in implementing a simple transformer goes into
providing an implementation of
[`SyncTransformer`](https://github.com/fusepoolP3/p3-transformer-library/blob/master/src/main/java/eu/fusepool/p3/transformer/SyncTransformer.java),
a compact interface with two methods of its own:

```java
public interface SyncTransformer extends Transformer {

	Entity transform(HttpRequestEntity entity) throws IOException;
    
    /**
     * Indicates if the transform method performs a long running task. In this
     * case the server will exposes the service using the asynchronous protocol.
     * 
     * @return true if this is a long running task, false otherwise
     */
    boolean isLongRunning();
}
```

plus two methods inherited from [`Transformer`](https://github.com/fusepoolP3/p3-transformer-library/blob/master/src/main/java/eu/fusepool/p3/transformer/Transformer.java):

```java

public interface Transformer {

    Set<MimeType> getSupportedInputFormats();

    Set<MimeType> getSupportedOutputFormats();
    
}
```

We will start with the two methods from `Transformer`, which tell the
library which input and output formats our `Transformer` consumes and
produces, respectively. These will be later used by the platform to
understand which transfomers can be wired together into longer
pipelines. Since we produce and consume `text/plain`, we just have to
provide that information:

```java
public class SedTransformer {

	private static final MimeType MIME_TEXT_PLAIN;
	
	static {
		try {
			MIME_TEXT_PLAIN = new MimeType("text/plain");
		} catch (MimeTypeParseException ex) {
			// Should never happen.
			throw new RuntimeException("Internal error.");
		}
	}

	private static final Set<MimeType> IO_FORMAT = Collections
		.unmodifiableSet(new HashSet<MimeType>() {{
			add(MIME_TEXT_PLAIN);
	    }});

	@Override
    public Set<MimeType> getSupportedInputFormats() {
        return IO_FORMAT;
    }


    @Override
    public Set<MimeType> getSupportedOutputFormats() {
        return IO_FORMAT;
    }
	
}
```

Now that we have taken care of the bureaucracy, we can focus on the
implementation of the actual transformation method: `transform` takes
as input an
[`HttpRequestEntity`](https://github.com/fusepoolP3/p3-transformer-library/blob/master/src/main/java/eu/fusepool/p3/transformer/HttpRequestEntity.java),
and returns a generic data
[`Entity`](https://github.com/fusepoolP3/p3-transformer-commons/blob/master/src/main/java/eu/fusepool/p3/transformer/commons/Entity.java)
as a response. Our implementation---stripped of checking code to avoid
obscuring it---is shown next.

```java
@Override
public Entity transform(HttpRequestEntity entity) {

    // 1. Reads the parameter containing the sed script.
	String script = entity.getRequest().getParameter("script");

	// 2. Reads the text to be transformed.
	String original = IOUtils.toString(entity.getData());

    // 3. Transforms.
	final String transformed = Unix4j
	         .fromString(readData(entity))
             .sed(script)
		     .toStringResult();

    // 4. Sends back the reply.
	return new WritingEntity() {
		@Override
        public MimeType getType() { return MIME_TEXT_PLAIN; }

	    @Override
		public void writeData(OutputStream out) throws IOException {
            out.write(transformed.getBytes());
		}
	};
}

```

This implementation does basically four things:

1. retrieves a request parameter (which should be supplied by the
   client in the URL query string when invoking the transformer)
   containing the `sed` script to be applied;
2. reads in the content to be transformed in memory using an
   [Apache commons IO](http://commons.apache.org/proper/commons-io/)
   utility;
3. applies the transform to the text by invoking `sed` through Unix4j;
4. creates and returns a
   [`WritingEntity`](https://github.com/fusepoolP3/p3-transformer-commons/blob/master/src/main/java/eu/fusepool/p3/transformer/commons/Entity.java)---
   a special type of `Entity`---to use as the content for the
   reply. When using `WritingEntity`, we just have to supply the MIME
   type of the output (by overriding `getType`), and a way to write
   the contents of the transformation to an `OutputStream` (by
   overriding `writeData`). For a finer-grained control, we could also
   implement the
   [Entity](https://github.com/fusepoolP3/p3-transformer-commons/blob/master/src/main/java/eu/fusepool/p3/transformer/commons/Entity.java)
   interface directly.

Note that we have not discussed the `isLongRunning` method---this is
because it will be the subject of our
[next example](#user-content-complex). For now, we assume it is just
overridden to always return `false`.

The last missing piece is how to run the transformer as an HTTP
server, and the library also provides a simple way to achieve
that. For our example, we'll just add a `main` method to
`SedTransformer`, containing:

```java
public static void main(String [] args) throws Exception {
	TransformerServer server = new TransformerServer(Integer.parseInt(args[0]));
	server.start(new SedTransformer());
	server.join();
}
```

This creates a
[`TransformerServer`](https://github.com/fusepoolP3/p3-transformer-library/blob/master/src/main/java/eu/fusepool/p3/transformer/server/TransformerServer.java)
(a Jetty-based HTTP server) and runs it on the port specified by the
first command line argument. To make it easier to manage dependencies
and run the example, you can download the code and a Maven project
[here](https://github.com/fusepoolP3/p3-transformer-howto/tree/master/transformer-sed).

To build and run, switch to the project folder and run:

```bash
mvn package
java -cp ./target/transformer-howto-1.0-jar-with-dependencies.jar p3.fusepool.transformers.sed.SedTransformer 8080
```

since we implemented `SyncTransformer`, the transformer it complies to
the [synchronous transformer API](). We can now start transforming!
Posting:

```bash
curl -XPOST -H 'Content-Type: text/plain' -d 'Hello, World!' 'http://localhost:8080?script=s/Hello/Goodbye/'
```

should print:

```bash
Goodbye, World!
```

## <a name="complex"></a> Making `SedTransformer` Asynchronous

`SedTransformer` is a _synchronous_ transformer---i.e., it does not
reply to the client until it is done transforming. This may not be
always a good idea, especially for transformers that take _long_ to
perform their tasks.  For such kind of long-running transformations,
it is best to write an _asynchronous_ transformer instead.

## Wrapping With `LongRunningTransformerWrapper`

A very simple way to make `SedTransformer` asynchronous is to change
`isLongRunning` so that it always returns `true`:

```java
@Override public boolean isLongRunning() { return true; }
```

This will cause our implementation to be wrapped inside an instance of
[`LongRunningTransformerWrapper`](https://github.com/fusepoolP3/p3-transformer-library/blob/master/src/main/java/eu/fusepool/p3/transformer/LongRunningTransformerWrapper.java)
by the runtime, effectively making it asynchronous. Now, when we post
the transformation with `curl`, instead of an immediate response, we
get: <a name="async-curl"></a>

```bash
HTTP/1.1 202 Accepted
Location: /job/f3b6317f-ad60-4f75-b8f1-00a7f2ec4602
```

which means, as per the asynchronous API, that the transformer is now
working on the transformation, and the client is free to go about its
business in the meantime. The path
`/job/f3b6317f-ad60-4f75-b8f1-00a7f2ec4602` represents the
transformation job, and it can be polled by issuing a GET request:

```bash
curl -i -XGET 'http://localhost:8080/job/f3b6317f-ad60-4f75-b8f1-00a7f2ec4602'
```

which will either return an HTTP 202, if the transformation has not
yet completed, or `Goodbye, World!` (with an HTTP 200) if it is.

## A "Native" Asynchronous Transformer

Although overriding `isLongRunning` is simple, it will not always
suffice. For example, `LongRunningTransformerWrapper` creates a thread
per POST request, which may not be always be desirable; i.e., we may
want to use a queue and a thread pool to keep bounds on server-side
resource usage instead. In cases where such kind of customization is
desired, it is best to implement the
[`AsyncTransformer`](https://github.com/fusepoolP3/p3-transformer-library/blob/master/src/main/java/eu/fusepool/p3/transformer/AsyncTransformer.java)
interface directly.

### The `AsyncTransformer` Interface

The `AsyncTransformer` interface differs from `SyncTransformer` in two
major ways:

```java
public interface AsyncTransformer extends Transformer {

    public interface  CallBackHandler {
        abstract void responseAvailable(String requestId, Entity response);

        public void reportException(String requestId, Exception ex);
    }

    void activate(CallBackHandler callBackHandler);
    
    void transform(HttpRequestEntity entity, String requestId) throws IOException;
    
    /**
     * Checks if a requestId is being processed by the Transformer. The Transformer
     * should return true if CallBackHandler.responseAvailable might be called
     * for the given requestId.
     * 
     * @param requestId the requestId
     * @return true if the Transformer is processing a request, false otherwise.
     */
    boolean isActive(String requestId);

}
```

1. Its transform method is of type `void` and takes a `requestId` in
   addition to the `HttpRequestEntity` from before;
2. it contains two extra methods: `activate`, and `isActive`.

The role of `requestId` is to uniquely identify each transformation
(POST) request. A different, unique `requestId` is automatically
generated by the library with every transformation request and
supplied as a parameter to the `transform` method. Asynchronous
transformers should keep track of such ids for when later the client
issues GET requests to inquiry on their completion status. Ids can
most of the times be stored in memory, though using some sort of
permanent storage to survive crashes is also an option.

`isActive` is the method called by the library to query on the status
of a request. It should return `true` if the request is complete, or
`false` otherwise.

Finally, `activate` is a lifecycle method. It will be called at
transformer startup (before any requests are dispatched) to install a
[CallBackHandler]. `CallBackHandlers` are going to be later used to
report results asynchronously to clients.

## Implementing a Queuing `sed` Transformer

We start by providing an implementation for the `transform` method. To
make things easier (e.g. reusing the
`getSupportedInputFormats`/`getSupportedOutputFormats`) we make our
asynchronous transformer extend the synchronous one.

```java
public class AsyncSedTransformer extends SedTransformer implements AsyncTransformer {

    private final static int MAX_REQUEST_BACKLOG = 100;

    private final LinkedBlockingQueue<String> fQueue = new LinkedBlockingQueue<String>(MAX_REQUEST_BACKLOG);

    private final ConcurrentHashMap<String, HttpRequestEntity> fActive = new ConcurrentHashMap<String, HttpRequestEntity>();

    private volatile CallBackHandler fCallback;

    @Override
    public void transform(HttpRequestEntity entity, String requestId) throws IOException {

        if (!fQueue.offer(requestId)) {
            throw new TooManyRequests("Too many requests on backlog.");
        }

        // This should generally not be a problem as we don't expect requestId
        // collisions.
        fActive.put(requestId, entity);
    }
```

As we can see, the only thing `transform` does is queuing the request
and, in case it manages to do so, it also stores the corresponding
`HttpRequestEntity` in a concurrent `Map`. If queuing fails (because
there are too many pending requests), it simply throws an exception,
which will be reported back to the client as an HTTP 500. With this in
place, the next method, `isActive`, is rather straightforward to do:

```java
public boolean isActive(String requestId) {
	return fActive.hasKey(requestId);
}
```

The next step is to provide the code to actually dequeue and process
the requests. We use a simple single threaded processor, which basically:

1. gets the next available `requestId` from the queue;
2. processes it using the `transform` method inherited from the
   synchronous transformer;
3.  reports the results back to the callback handler if the
   transformation ends well. Otherwise, it reports an exception.

```java
class SimpleExecutor implements Runnable {

    @Override
    public void run() {
		try {
			while (true) {
				processRequest(fQueue.poll(Long.MAX_VALUE, TimeUnit.DAYS));
			}
		} catch (InterruptedException ex) {
			// Just restores interruption state.
			Thread.currentThread().interrupt();
		}
	}

	public void processRequest(String requestId) {
	    // Resolves the associated HttpRequestEntity.
		HttpRequestEntity entity = fActive.get(requestId);

	    // Does the actual transformation.
	    try {
            fCallback.responseAvailable(requestId, transform(entity));
        } catch (Exception ex) {
			fCallback.reportException(requestId, ex);
		}
	}
}

```

The final method to be written is `activate`, which installs the
callback handler and starts the simple executor:

```java
@Override
public void activate(CallBackHandler callBackHandler) {
	fCallback = callBackHandler;
    new Thread(new SimpleExecutor()).start();
}
```

Since we took the decision to embed the code to start the server from
the command line in the class itself, we also have to repeat this
procedure here. Clearly, better approaches possible, but this suffices
for our simple example. We only show the relevant parts of the `main`
function:

```
public static void main(String[] args) throws Exception {

	...

    TransformerServer server = new TransformerServer(Integer.parseInt(args[0]));
    server.start(new AsyncSedTransformer());

	...
}
```

Again, the project with a Maven build can be found
[here](https://github.com/fusepoolP3/p3-transformer-howto/tree/master/transformer-sed). After
packaging, the asynchronous transformer can be run with:

```
java -cp ./target/transformer-howto-1.0-jar-with-dependencies.jar p3.fusepool.transformers.sed.SedTransformer 8080
```

and we can interact it in a similar way as [before](#user-content-async-curl), by means of the
asynchronous API.
