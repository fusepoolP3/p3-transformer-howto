package eu.fusepool.p3.transformer.sed;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import javax.servlet.http.HttpServletRequest;

import eu.fusepool.p3.transformer.HttpRequestEntity;
import eu.fusepool.p3.transformer.SyncTransformer;
import eu.fusepool.p3.transformer.commons.Entity;
import eu.fusepool.p3.transformer.commons.util.WritingEntity;
import eu.fusepool.p3.transformer.server.TransformerServer;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unix4j.Unix4j;

public class SedTransformer implements SyncTransformer {

    private static final Logger fLogger = LoggerFactory.getLogger(SedTransformer.class);

    public static final String PAR_SCRIPT = "script";

    private static final MimeType MIME_TEXT_PLAIN = mimeType("text", "plain");

    @SuppressWarnings("serial")
    private static final Set<MimeType> INPUT_FORMATS = Collections
            .unmodifiableSet(new HashSet<MimeType>() {{
                add(MIME_TEXT_PLAIN);
            }});

    private static final Set<MimeType> OUTPUT_FORMATS = INPUT_FORMATS;


    @Override
    public Set<MimeType> getSupportedInputFormats() {
        return INPUT_FORMATS;
    }


    @Override
    public Set<MimeType> getSupportedOutputFormats() {
        return OUTPUT_FORMATS;
    }


    @Override
    public Entity transform(HttpRequestEntity entity) throws IOException {
        String sedSpec = checkedGet(entity, PAR_SCRIPT);

        final String transformed = Unix4j
                .fromString(readData(entity))
                .sed(sedSpec)
                .toStringResult();

        return wrapInEntity(transformed);
    }

    private WritingEntity wrapInEntity(final String transformed) {
        return new WritingEntity() {
            @Override
            public MimeType getType() {
                return MIME_TEXT_PLAIN;
            }

            @Override
            public void writeData(OutputStream out) throws IOException {
                out.write(transformed.getBytes());
            }
        };
    }

    private String checkedGet(HttpRequestEntity entity, String parameter) {
        String par = entity.getRequest().getParameter(parameter);
        // If there's no regex, throws Exception
        if (par == null) {
            throw new RuntimeException("Missing parameter " + parameter + ".");
        }
        return par;
    }

    @Override
    public boolean isLongRunning() {
        return true;
    }


    private String readData(HttpRequestEntity entity) throws IOException {
        return IOUtils.toString(entity.getData(), charsetOf(entity.getRequest()));
    }


    private String charsetOf(HttpServletRequest request) {

        String encoding = request.getCharacterEncoding();

        if (encoding == null) {
            fLogger.error("Cannot resolve encoding " + encoding + ". Defaulting to US-ASCII.");
            encoding = "US-ASCII";
        }

        return encoding;
    }


    private static MimeType mimeType(String primary, String sub) {

        try {
            return new MimeType(primary, sub);
        } catch (MimeTypeParseException ex) {
            throw new RuntimeException("Internal error.");
        }
    }

    public static void main(String[] args) throws Exception {

        if (args.length < 1) {
            System.err.println("Missing port parameter.\n" +
                    "Usage: " + SedTransformer.class.getSimpleName() + " [PORT]");
            System.exit(-1);
        }

        TransformerServer server = new TransformerServer(Integer.parseInt(args[0]));
        server.start(new SedTransformer());

        try {
            server.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
