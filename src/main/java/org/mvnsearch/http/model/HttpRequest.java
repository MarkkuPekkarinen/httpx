package org.mvnsearch.http.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.Nullable;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

@SuppressWarnings({"unused", "DuplicatedCode"})
public class HttpRequest {
    private Integer index;
    private String name;
    private String comment;
    private List<String> tags;
    private HttpMethod method;
    private List<HttpHeader> headers;
    private List<String> bodyLines;
    private byte[] body;
    private String jsTestCode;
    private String redirectResponse;
    private HttpRequestTarget requestTarget;

    public HttpRequest() {
    }

    public HttpRequest(Integer index) {
        this.index = index;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public String getName() {
        return name == null ? index.toString() : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public void addTag(String tag) {
        if (this.tags == null) {
            this.tags = new ArrayList<>();
        }
        this.tags.add(tag);
    }

    public HttpMethod getMethod() {
        return method;
    }

    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    public HttpRequestTarget getRequestTarget() {
        return requestTarget;
    }

    public void setRequestTarget(HttpRequestTarget requestTarget) {
        this.requestTarget = requestTarget;
    }

    public List<HttpHeader> getHeaders() {
        return headers;
    }

    public void setHeaders(List<HttpHeader> headers) {
        this.headers = headers;
    }

    @Nullable
    public String getHeader(String name) {
        if (this.headers != null) {
            for (HttpHeader header : headers) {
                if (header.getName().equals(name)) {
                    return header.getValue();
                }
            }
        }
        return null;
    }

    public void addHttpHeader(String name, String value) {
        if (headers == null) {
            headers = new ArrayList<>();
        }
        if (name.equalsIgnoreCase("authorization")) {
            // Convert `username password` or `username:password` to Base64
            if (value.startsWith("Basic ")) {
                if (value.contains(" ") || value.contains(":")) {
                    String text = value.replace(" ", ":");
                    value = "Basic " + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
                }
            }
        } else if (name.equalsIgnoreCase("host")) {
            requestTarget.setHostHeader(value);
        }
        this.headers.add(new HttpHeader(name, value));
    }

    public List<String> getBodyLines() {
        return bodyLines;
    }

    public void setBodyLines(List<String> bodyLines) {
        this.bodyLines = bodyLines;
    }

    public void addBodyLine(String line) {
        if (this.bodyLines == null) {
            bodyLines = new ArrayList<>();
        }
        this.bodyLines.add(line);
    }

    public String getRedirectResponse() {
        return this.redirectResponse;
    }

    public String getJavaScriptTestCode() {
        return this.jsTestCode;
    }

    public byte[] getBodyBytes() {
        return this.body != null ? this.body : new byte[]{};
    }

    public Mono<ByteBuf> requestBody() {
        return Mono.create(sink -> {
            final byte[] bodyBytes = getBodyBytes();
            if (bodyBytes == null || bodyBytes.length == 0) {
                sink.success();
            } else {
                sink.success(Unpooled.wrappedBuffer(bodyBytes));
            }
        });
    }

    public boolean isFilled() {
        return method != null && requestTarget != null;
    }

    public boolean isBodyEmpty() {
        return bodyLines == null || bodyLines.isEmpty();
    }

    /**
     * clean body: extract javascript test code, redirect response etc
     */
    public void cleanBody() throws Exception {
        if (bodyLines != null && !bodyLines.isEmpty()) {
            if (bodyLines.get(0).startsWith("< ")) { // load body from an external file
                String firstLine = bodyLines.get(0);
                String fileName = firstLine.substring(1).trim();
                this.body = Files.readAllBytes(Path.of(fileName));
            } else {
                List<String> lines = new ArrayList<>();
                for (String bodyLine : bodyLines) {
                    if (!bodyLine.startsWith("<>")) {
                        lines.add(bodyLine);
                    }
                }
                // extract js code block
                int jsScriptStartOffset = lines.size();
                int jsScriptEndOffset = -1;
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.startsWith("> {%")) {
                        jsScriptStartOffset = i;
                    }
                    if (line.equals("%}") && i > jsScriptStartOffset) {
                        jsScriptEndOffset = i;
                        break;
                    }
                }
                if (jsScriptEndOffset > 0) { // javascript test code found
                    this.jsTestCode = String.join(System.lineSeparator(), lines.subList(jsScriptStartOffset + 1, jsScriptEndOffset));
                    List<String> cleanLines = new ArrayList<>();
                    cleanLines.addAll(lines.subList(0, jsScriptStartOffset));
                    cleanLines.addAll(lines.subList(jsScriptEndOffset + 1, lines.size()));
                    lines = cleanLines;
                }
                // extract js file '> /path/to/responseHandler.js'
                List<String> jsHandlerFiles = new ArrayList<>();
                for (String line : lines) {
                    if (line.startsWith("> ") && line.endsWith(".js")) { // response redirect
                        jsHandlerFiles.add(line);
                    }
                }
                if (!jsHandlerFiles.isEmpty()) {
                    lines.removeAll(jsHandlerFiles);
                    //read js block from files
                }
                //extract redirect response file
                for (String line : lines) {
                    if (line.startsWith(">>")) { // response redirect
                        this.redirectResponse = line;
                    }
                }
                if (this.redirectResponse != null) {
                    lines.remove(this.redirectResponse);
                }
                //remove line breaks at then end of text
                if (!lines.isEmpty()) {
                    while (Objects.equals(lines.get(lines.size() - 1), "")) {
                        lines.remove(lines.size() - 1);
                    }
                    String content = String.join(System.lineSeparator(), lines);
                    this.body = content.getBytes(StandardCharsets.UTF_8);
                }
            }
        }
    }
}
