package com.fentai.arcmenu.editor;

import com.google.gson.*;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import com.fentai.arcmenu.protocol.MarketTls;
import java.util.Objects;

/** HTTPS metadata client. Minecraft session credentials never enter a marketplace request. */
public final class MarketClient {
    private volatile HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile String base="", certificate="", token="", userId="", userName="";
    private volatile long expiry;
    private volatile long generation;
    private final MarketImageCache images=new MarketImageCache(24*1024*1024);
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"ArcMenu-market-auth-deadline");thread.setDaemon(true);return thread;});
    private final Duration signInTimeout;
    private Attempt signInAttempt;
    private String authenticatedIdentity="", signInError="", signInStage="";
    private boolean signInFailed;
    private static final class Attempt {
        final String identity;
        final long generation;
        final CompletableFuture<JsonObject> result=new CompletableFuture<>();
        volatile CompletableFuture<JsonObject> source;
        ScheduledFuture<?> deadline;
        boolean finished;
        Attempt(String identity,long generation){this.identity=identity;this.generation=generation;}
    }
    public MarketClient(){this(Duration.ofSeconds(25));}
    MarketClient(Duration signInTimeout){if(signInTimeout.isNegative()||signInTimeout.isZero())throw new IllegalArgumentException("Sign-in timeout must be positive");this.signInTimeout=signInTimeout;}
    public long generation(){return generation;}
    public String endpoint(){return base;}
    public String certificateSha256(){return certificate;}
    public void endpoint(String value,String pin) {
        String normalized=value==null?"":value.strip().replaceAll("/+$","");
        pin=pin==null?"":pin;
        final Attempt cancelled;
        synchronized(this){
            if(normalized.equals(base)&&pin.equals(certificate))return;
            var configured=MarketTls.httpClient(pin);
            cancelled=resetAuthentication();generation++;base=normalized;certificate=pin;http=configured;
        }
        cancelAttempt(cancelled);
        images.clear();
    }
    public boolean available() { try { var uri=URI.create(base);return "https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null; } catch(RuntimeException e){return false;} }
    public boolean authenticated() { return !token.isEmpty()&&System.currentTimeMillis()/1000<expiry; }
    public synchronized String token() { if(!authenticated())throw new IllegalStateException("Marketplace authentication has expired");return token; }
    public String userId() { return userId; }
    public String userName() { return userName; }
    public synchronized String authenticationStatus() {return authenticated()?"signed_in":signInAttempt!=null&&!signInAttempt.finished?"signing_in":signInFailed?"failed":available()?"guest":"unavailable";}
    public synchronized String authenticationError(){return signInError;}
    public synchronized String authenticationStage(){return signInStage;}
    public synchronized void stage(String identity,String value){if(signInAttempt!=null&&!signInAttempt.finished&&signInAttempt.identity.equals(identity))signInStage=value;}
    public synchronized Optional<MarketSessionCache.Entry> sessionEntry(){
        if(!authenticated())return Optional.empty();
        try{return Optional.of(new MarketSessionCache.Entry(base,certificate,userId,userName,token,expiry));}
        catch(RuntimeException error){return Optional.empty();}
    }
    /** A failed automatic attempt stays quiet until an explicit retry or a changed connection. */
    public CompletableFuture<JsonObject> signIn(String identity,boolean retry,Supplier<CompletableFuture<JsonObject>> operation){
        final Attempt attempt;
        final Attempt cancelled;
        synchronized(this){
            if(authenticated()&&(identity.equals(authenticatedIdentity)||profileMatches(identity,userId)))return CompletableFuture.completedFuture(identityReply());
            if(signInAttempt!=null&&!signInAttempt.finished&&identity.equals(signInAttempt.identity))return signInAttempt.result;
            if(!retry&&signInFailed&&signInAttempt!=null&&identity.equals(signInAttempt.identity))return signInAttempt.result;
            cancelled=retireAttempt(signInAttempt);
            token=userId=userName=authenticatedIdentity="";expiry=0;signInFailed=false;signInError="";signInStage="challenge";
            attempt=new Attempt(identity,generation);signInAttempt=attempt;
            attempt.result.whenComplete((reply,error)->{if(attempt.result.isCancelled())finish(attempt,null,new CancellationException("Marketplace sign-in was cancelled"));});
            attempt.deadline=DEADLINES.schedule(()->finish(attempt,null,new TimeoutException("Marketplace sign-in timed out; retry when the connection is available")),signInTimeout.toNanos(),TimeUnit.NANOSECONDS);
        }
        cancelAttempt(cancelled);
        try{
            var source=Objects.requireNonNull(operation.get(),"Authentication operation returned no future");
            final boolean discard;
            synchronized(this){attempt.source=source;discard=signInAttempt!=attempt||attempt.finished;}
            if(discard)source.cancel(true);
            source.whenComplete((reply,error)->finish(attempt,reply,error));
        }catch(RuntimeException error){finish(attempt,null,error);}
        return attempt.result;
    }
    private void finish(Attempt attempt,JsonObject reply,Throwable error){
        CompletableFuture<JsonObject> cancel=null;
        synchronized(this){
            if(signInAttempt!=attempt||attempt.finished||attempt.generation!=generation)return;
            if(attempt.result.isCancelled())error=new CancellationException("Marketplace sign-in was cancelled");
            if(error==null){
                try{
                    var entry=new MarketSessionCache.Entry(base,certificate,reply.get("uuid").getAsString(),reply.get("username").getAsString(),reply.get("token").getAsString(),reply.get("expiresAt").getAsLong());
                    if(entry.expiresAt()<=System.currentTimeMillis()/1000||!profileMatches(attempt.identity,entry.userId()))throw new IllegalStateException("Marketplace identity does not match the current Minecraft account or has expired");
                    token=entry.token();userId=entry.userId();userName=entry.userName();expiry=entry.expiresAt();authenticatedIdentity=attempt.identity;
                }catch(RuntimeException invalid){error=new IllegalStateException("Marketplace returned an invalid authentication session",invalid);}
            }
            attempt.finished=true;if(attempt.deadline!=null)attempt.deadline.cancel(false);
            signInFailed=error!=null;signInError=error==null?"":message(error);signInStage=error==null?"signed_in":"failed";
            if(error!=null)cancel=attempt.source;
        }
        if(error==null)attempt.result.complete(reply);else attempt.result.completeExceptionally(error);
        if(cancel!=null)cancel.cancel(true);
    }
    private static boolean profileMatches(String identity,String id){String profile=identity.substring(identity.lastIndexOf(':')+1);return profile.replace("-","").equalsIgnoreCase(id.replace("-",""));}
    private JsonObject identityReply(){var reply=new JsonObject();reply.addProperty("token",token);reply.addProperty("uuid",userId);reply.addProperty("username",userName);reply.addProperty("expiresAt",expiry);return reply;}
    /** State invalidation holds the client monitor; future callbacks always run after it is released. */
    private static Attempt retireAttempt(Attempt attempt){if(attempt==null||attempt.finished)return null;attempt.finished=true;if(attempt.deadline!=null)attempt.deadline.cancel(false);return attempt;}
    private static void cancelAttempt(Attempt attempt){if(attempt==null)return;attempt.result.cancel(false);var source=attempt.source;if(source!=null)source.cancel(true);}
    private static String message(Throwable error){while(error.getCause()!=null&&(error instanceof CompletionException||error instanceof ExecutionException))error=error.getCause();return Objects.toString(error.getMessage(),error.getClass().getSimpleName());}
    public void authenticate(JsonObject reply) {
        String nextToken=reply.get("token").getAsString(),nextUserId=reply.get("uuid").getAsString(),nextUserName=reply.get("username").getAsString();long nextExpiry=reply.get("expiresAt").getAsLong();
        final Attempt cancelled;
        synchronized(this){cancelled=resetAuthentication();token=nextToken;userId=nextUserId;userName=nextUserName;expiry=nextExpiry;signInStage=authenticated()?"signed_in":"";}
        cancelAttempt(cancelled);
    }
    public void clearAuthentication() {final Attempt cancelled;synchronized(this){cancelled=resetAuthentication();}cancelAttempt(cancelled);}
    private Attempt resetAuthentication(){var cancelled=retireAttempt(signInAttempt);token=userId=userName=authenticatedIdentity="";expiry=0;signInAttempt=null;signInError=signInStage="";signInFailed=false;return cancelled;}
    public static final class RequestException extends IllegalStateException {
        private final int statusCode;
        private final String code;
        public RequestException(int statusCode,String code,String message){super(message);this.statusCode=statusCode;this.code=code==null?"http_error":code;}
        public int statusCode(){return statusCode;}
        public String code(){return code;}
    }
    public static boolean isTransient(Throwable error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof SSLHandshakeException||cause instanceof SSLPeerUnverifiedException||cause instanceof CertificateException||cause instanceof CancellationException||cause instanceof InvalidCredentialsException)return false;
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof RequestException request)return request.statusCode()==429||request.statusCode()==502||request.statusCode()==503||request.statusCode()==504||request.code().equals("mojang_unavailable")||request.code().equals("join_not_verified");
            if(cause instanceof IOException||cause instanceof TimeoutException||cause instanceof AuthenticationUnavailableException)return true;
        }
        return false;
    }
    public CompletableFuture<JsonObject> request(String method,String path,JsonObject body,boolean requireAuth) {
        if(!available())return CompletableFuture.failedFuture(new IllegalStateException("Marketplace HTTPS endpoint is unavailable"));
        try {
            final HttpClient transport;final long requestGeneration;final HttpRequest.Builder request;final String requestToken;
            synchronized(this){transport=http;requestGeneration=generation;requestToken=requireAuth?token():"";request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30)).header("Accept","application/json");if(requireAuth)request.header("Authorization","Bearer "+requestToken);}
            if(body==null) request.method(method,HttpRequest.BodyPublishers.noBody());
            else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body.toString(),StandardCharsets.UTF_8));
            var transportRequest=transport.sendAsync(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var result=transportRequest.thenApply(response->handleResponse(response.statusCode(),response.body(),requestGeneration,requestToken,requireAuth));
            result.whenComplete((reply,error)->{if(result.isCancelled())transportRequest.cancel(true);});
            return result;
        }catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
    }
    JsonObject handleResponse(int statusCode,String body,long requestGeneration,String requestToken,boolean requireAuth){
        if(requestGeneration!=generation)throw new CompletionException(new IllegalStateException("Marketplace connection changed during request"));
        JsonObject result=null;
        try {result=JsonParser.parseString(body).getAsJsonObject();}catch(RuntimeException ignored){}
        if(statusCode<200||statusCode>=300){
            String message="Marketplace HTTP "+statusCode,code="http_error";
            if(result!=null&&result.has("error")&&result.get("error").isJsonObject()){var failure=result.getAsJsonObject("error");message=WorkspaceState.string(failure,"message",message);code=WorkspaceState.string(failure,"code",code);}
            if(requireAuth&&(statusCode==401||statusCode==403)&&code.equals("invalid_token")){
                Attempt cancelled=null;
                synchronized(this){if(generation==requestGeneration&&token.equals(requestToken)){cancelled=resetAuthentication();signInFailed=true;signInError=message;signInStage="failed";}}
                cancelAttempt(cancelled);
            }
            throw new CompletionException(new RequestException(statusCode,code,message));
        }
        if(result==null)throw new CompletionException(new IllegalStateException("Marketplace returned an invalid JSON response"));
        return result;
    }
    public CompletableFuture<byte[]> image(String url) {
        try {
            URI uri=URI.create(url); if(!"https".equalsIgnoreCase(uri.getScheme()))throw new IllegalArgumentException("Marketplace previews require HTTPS");
            final HttpClient transport;final long imageGeneration;
            synchronized(this){transport=http;imageGeneration=generation;}
            return images.get(imageGeneration+":"+url,()->transport.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofByteArray()).thenApply(response->{
                if(imageGeneration!=generation)throw new CompletionException(new IllegalStateException("Marketplace connection changed during preview download"));
                if(response.statusCode()!=200||response.body().length>8*1024*1024)throw new CompletionException(new IllegalStateException("Marketplace preview download failed"));return response.body();
            }));
        }catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
    }
    public static String path(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
}
