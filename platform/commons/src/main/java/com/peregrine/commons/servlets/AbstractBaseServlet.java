package com.peregrine.commons.servlets;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestDispatcherOptions;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * Base Class for Peregrine Servlets
 *
 * This class requests a handleRequest() method to the
 * sub classes with one Request and one Reponse object.
 * It will then handle the response and return it back to the
 * caller in a consistent manner including errors.
 *
 * Created by Andreas Schaefer on 6/20/17.
 */
public abstract class AbstractBaseServlet
    extends SlingAllMethodsServlet
{
    protected final Logger logger = LoggerFactory.getLogger(getClass());

//    private boolean allowAll = false;

    public AbstractBaseServlet() {
    }

//    public AbstractBaseServlet(boolean allowAll) {
//        this.allowAll = allowAll;
//    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
        doRequest(request, response);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
        doRequest(request, response);
    }

    private void doRequest(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException, ServletException {
        Response out = null;
        try {
            out = handleRequest(new Request(request, response));
            if(out == null) {
                out = new ErrorResponse().setHttpErrorCode(HttpServletResponse.SC_INTERNAL_SERVER_ERROR).setErrorCode(-123).setErrorMessage("Servlet did not return a Response");
            }
        } catch(IOException e) {
            out = new ErrorResponse().setHttpErrorCode(HttpServletResponse.SC_INTERNAL_SERVER_ERROR).setErrorCode(-124).setErrorMessage("Failed with IO exception").setException(e);
        } catch(RuntimeException e) {
            out = new ErrorResponse().setHttpErrorCode(HttpServletResponse.SC_INTERNAL_SERVER_ERROR).setErrorCode(-125).setErrorMessage("Failed with runtime exception").setException(e);
        } catch(Error e) {
            // Do not swallow errors as the system needs to handle that -> log and rethrow
            logger.debug("Servlet Request failed with Error", e);
            throw e;
        }
        if(!"alreadyHandled".equals(out.getType())) {
            response.setContentType(out.getMimeType());
            String output = out.getContent();
            if("direct".equals(out.getType())) {
                out.handleDirect(request, response);
            } else {
                if("error".equals(out.getType())) {
                    ErrorResponse error = (ErrorResponse) out;
                    response.setStatus(error.getHttpErrorCode());
                }
                if(output == null) {
                    out.writeTo(response.getOutputStream());
                } else {
                    logger.trace("Servlet Response: '{}'", output);
                    response.getWriter().write(output);
                }
                response.flushBuffer();
            }
        }
    }

    protected abstract Response handleRequest(Request request) throws IOException, ServletException;

    /**
     * Wrapper Object for the Request which contains the Sling Http Servlet Request and Response
     * as well as parameters
     */
    public static class Request {
        private SlingHttpServletRequest request;
        private SlingHttpServletResponse response;
        private Map<String, String> parameters = new HashMap<>();

        public Request(SlingHttpServletRequest request, SlingHttpServletResponse response) {
            this.request = request;
            this.response = response;
            this.parameters = ServletHelper.obtainParameters(request);
        }

        public SlingHttpServletRequest getRequest() {
            return request;
        }

        public SlingHttpServletResponse getResponse() {
            return response;
        }

        public Map<String, String> getParameters() {
            return parameters;
        }

        public String getParameter(String name) {
            return getParameter(name, null);
        }

        public String getParameter(String name, String defaultValue) {
            String answer = parameters.get(name);
            return answer == null ? defaultValue : answer;
        }

        public int getIntParameter(String name, int defaultValue) {
            int answer = defaultValue;
            try {
                String parameter = parameters.get(name);
                answer = Integer.parseInt(parameter);
            } catch(NumberFormatException e) {
                // Ignore
            }
            return answer;
        }

        public String getRequestPath() { return request.getRequestURI(); }
        public ResourceResolver getResourceResolver() {
            return request.getResourceResolver();
        }

        public Resource getResource() { return request.getResource(); }

        public Resource getResourceByPath(String path) { return request.getResourceResolver().getResource(path); }

        public String getSelector() { return request.getRequestPathInfo().getSelectorString(); }

        public String getExtension() { return request.getRequestPathInfo().getExtension(); }

        public String getSuffix() { return request.getRequestPathInfo().getSuffix(); }

        public Collection<Part> getParts() throws IOException, ServletException { return request.getParts(); }
    }

    /**
     * Response Object which contains the type and provides the content
     */
    public static abstract class Response {
        private String type;

        public Response(String type) {
            this.type = type;
        }

        /** @return Type of the response **/
        public String getType() { return type; }

        /** @return Response Content as text **/
        public String getContent() throws IOException {
            return null;
        }

        /** @return Writes the content to a given Output Stream **/
        public void writeTo(OutputStream outputStream) throws IOException {
            throw new UnsupportedOperationException("Write To is not supported");
        }

        /** @return The Servlet handles the output by itself **/
        public void handleDirect(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException, ServletException {
            throw new UnsupportedOperationException("Handle Direct is not supported");
        }

        /** @return Mime Type of the Content **/
        public abstract String getMimeType();
    }

    /**
     * This represents a Response where the output was handled by the Servlet already
     */
    public static class ResponseHandledResponse
        extends Response {

        public ResponseHandledResponse() {
            super("alreadyHandled");
        }

        @Override
        public String getMimeType() {
            return null;
        }
    }

    /**
     * A JSon based content response. It provides helper methods
     * to create the response and will track the objects / arrays
     * created to cleanly close then appropriately.
     *
     * This class creates an JSon Object as the root.
     */
    public static class JsonResponse
        extends Response
    {

        private enum STATE { object, array };

        private JsonGenerator json;
        private StringWriter writer;
        private Stack<STATE> states = new Stack<>();

        public JsonResponse() throws IOException {
            this("json");
        }

        public JsonResponse(String type) throws IOException {
            super(type);
            init();
        }

        private void init() throws IOException {
            JsonFactory jf = new JsonFactory();
            writer = new StringWriter();
            json = jf.createGenerator(writer);
            json.useDefaultPrettyPrinter();
            json.writeStartObject();
            states.push(STATE.object);
        }

        /**
         * Adds the given map as child. This requires that a
         * field for that map was already created
         *
         * @param object Map to be serialized into JSon field value
         * @return This instance for method chaining
         * @throws IOException If creating the JSon representation of the Map failed or adding the value
         */
        public JsonResponse writeMap(Map object) throws IOException {
            StringWriter writer = new StringWriter();
            ObjectMapper mapper = new ObjectMapper();
            mapper.writeValue(writer, object);
            writer.close();
            String data = writer.toString();
            json.writeRaw(data.substring(1, data.length()-1));
            return this;
        }

        /**
         * Write a boolean field
         * @param name Name of the field
         * @param value Boolean value
         * @return This instance for method chaining
         * @throws IOException If writing the boolean field failed
         */
        public JsonResponse writeAttribute(String name, boolean value) throws IOException {
            json.writeBooleanField(name, value);
            return this;
        }

        /**
         * Write a number field
         * @param name Name of the field
         * @param value Number value
         * @return This instance for method chaining
         * @throws IOException If writing the number field failed
         */
        public JsonResponse writeAttribute(String name, int value) throws IOException {
            json.writeNumberField(name, value);
            return this;
        }
        /**
         * Write a text field
         * @param name Name of the field
         * @param value Text value
         * @return This instance for method chaining
         * @throws IOException If writing the text field failed
         */
        public JsonResponse writeAttribute(String name, String value) throws IOException {
            json.writeStringField(name, value);
            return this;
        }
        /**
         * Write a raw text field
         * @param name Name of the field
         * @param value Raw JSon text (needs to be formatted correctly)
         * @return This instance for method chaining
         * @throws IOException If writing the raw text field failed
         */
        public JsonResponse writeAttributeRaw(String name, String value) throws IOException {
            json.writeFieldName(name);
            json.writeRawValue(value);
            return this;
        }
//
//        public JsonResponse writeArray() throws IOException {
//            json.writeStartArray();
//            states.push(STATE.array);
//            return this;
//        }

        /**
         * Starts an JSon array field
         * @param name Name of the Array Field
         * @return This instance for method chaining
         * @throws IOException If starting the array field failed
         */
        public JsonResponse writeArray(String name) throws IOException {
            json.writeArrayFieldStart(name);
            states.push(STATE.array);
            return this;
        }
        /**
         * Starts an JSon object value
         * @return This instance for method chaining
         * @throws IOException If starting the object failed
         */
        public JsonResponse writeObject() throws IOException {
            json.writeStartObject();
            states.push(STATE.object);
            return this;
        }

        /**
         * Closes the current Object or Array
         * @return This instance for method chaining
         * @throws IOException If closing the current object / array failed
         */
        public JsonResponse writeClose() throws IOException {
            STATE last = states.pop();
            switch(last) {
                case object:
                    json.writeEndObject();
                    break;
                case array:
                    json.writeEndArray();
                    break;
                default:
                    // Nothing to do here
            }
            return this;
        }
        /**
         * Closes the entire JSon Object
         * @return This instance for method chaining
         * @throws IOException If closing the JSon failed
         */
        public JsonResponse writeCloseAll() throws IOException {
            while(!states.empty()) {
                writeClose();
            }
            json.close();
            return this;
        }

        @Override
        public String getContent() throws IOException {
            writeCloseAll();
            return writer.toString();
        }

        @Override
        public String getMimeType() {
            return "application/json";
        }
    }

    /**
     * JSon based Error Response
     */
    public static class ErrorResponse
        extends JsonResponse {

        private int httpErrorCode = HttpServletResponse.SC_BAD_REQUEST;

        public ErrorResponse() throws IOException {
            super("error");
        }

        /** @return Http Error Code set (Bad Request is default) **/
        public int getHttpErrorCode() {
            return httpErrorCode;
        }

        public ErrorResponse setHttpErrorCode(int httpErrorCode) {
            this.httpErrorCode = httpErrorCode;
            return this;
        }
        /** Sets the Error Code which is returned as 'code' number field **/
        public ErrorResponse setErrorCode(int code) throws IOException {
            return (ErrorResponse) writeAttribute("code", code);
        }
        /** Sets the Error Message which is returned as 'message' text field **/
        public ErrorResponse setErrorMessage(String message) throws IOException {
            return (ErrorResponse) writeAttribute("message", message);
        }
        /** Sets the Request Past which is returned as 'path' text field **/
        public ErrorResponse setRequestPath(String path) throws IOException {
            return (ErrorResponse) writeAttribute("path", path);
        }
        /** Sets the Custom Error Field which is returned as the provide name / value field **/
        public ErrorResponse setCustom(String fieldName, String value) throws IOException {
            return (ErrorResponse) writeAttribute(fieldName, value);
        }
        /** Sets an Exception that cause the Error which is written out a serialized exception **/
        public ErrorResponse setException(Exception e) throws IOException {
            StringWriter out = new StringWriter();
            e.printStackTrace(new PrintWriter(out));
            return (ErrorResponse) writeAttribute("exception", out.toString());
        }
    }
    /** Plain Text Response **/
    public static class TextResponse
        extends Response {

        private StringBuffer content;
        private String mimeType = "plain/text";

        public TextResponse(String type) {
            super(type);
        }

        public TextResponse(String type, String mimeType) {
            super(type);
            setMimeType(mimeType);
        }
        /** Adds the given text to the output **/
        public TextResponse write(String text) {
            content.append(text);
            return this;
        }
        /** Sets the Mime Type of the Output (plain/text is default) **/
        public TextResponse setMimeType(String mimeType) {
            if(mimeType != null && !mimeType.isEmpty()) {
                this.mimeType = mimeType;
            }
            return this;
        }

        @Override
        public String getContent() {
            return content.toString();
        }

        @Override
        public String getMimeType() {
            return mimeType;
        }
    }
    /** Response that redirects to another page **/
    public static class RedirectResponse
        extends Response
    {
        private String redirectTo;

        /**
         * Creates a Redirect Response Object
         * @param redirectTo Path to the Page to be redirected to
         * @throws IllegalArgumentException If the redirect path is null or empty
         */
        public RedirectResponse(String redirectTo) {
            super("direct");
            if(redirectTo == null || redirectTo.isEmpty()) {
                throw new IllegalArgumentException("Redirect To path must be provided");
            }
            this.redirectTo = redirectTo;
        }

        @Override
        public void handleDirect(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
            response.sendRedirect(redirectTo);
        }

        @Override
        public String getMimeType() {
            return null;
        }
    }
    /** Response that forwards to another page **/
    public static class ForwardResponse
        extends Response
    {
        private Resource resource;
        private RequestDispatcherOptions requestDispatcherOptions;

        /**
         * Creates a Forward Response
         * @param resource Resource to be forwarded to
         * @param requestDispatcherOptions Request Dispatcher Options
         * @throws IllegalArgumentException If the resource to be forwarded to is null
         */
        public ForwardResponse(Resource resource, RequestDispatcherOptions requestDispatcherOptions) {
            super("direct");
            if(resource == null) {
                throw new IllegalArgumentException("Redirect Resource must be provided");
            }
            this.resource = resource;
            this.requestDispatcherOptions = requestDispatcherOptions;
        }

        @Override
        public void handleDirect(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException, ServletException {
            request.getRequestDispatcher(resource, requestDispatcherOptions).forward(request, response);
        }

        @Override
        public String getMimeType() {
            return null;
        }
    }
}
