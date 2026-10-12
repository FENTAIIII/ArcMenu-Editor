package com.fentai.arcmenu.editor;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

class MarketClientTest {
    private static final String ACCOUNT="20a1028b-5be4-43d1-8e67-8bb9044d6ecf";
    private static final String OTHER_ACCOUNT="00000000-0000-0000-0000-000000000001";
    private static final String TOKEN="market_session_fixture_0123456789abcde";
    private static JsonObject session(String account){var json=new JsonObject();json.addProperty("token",TOKEN);json.addProperty("uuid",account);json.addProperty("username","DesignLab");json.addProperty("expiresAt",4102444800L);return json;}
    private static MarketClient configured(){var market=new MarketClient();market.endpoint("https://example.org","");return market;}
    private static final class UncancellableReply extends CompletableFuture<JsonObject> {@Override public boolean cancel(boolean interrupt){return false;}}
    @Test void endpointsRequireHttpsAndChangingServersDropsMarketCredentials(){var market=new MarketClient();market.endpoint("http://example.org","");assertFalse(market.available());market.endpoint("https://example.org/","");assertTrue(market.available());market.authenticate(JsonParser.parseString("{\"token\":\"market-only\",\"uuid\":\"id\",\"username\":\"name\",\"expiresAt\":4102444800}").getAsJsonObject());assertTrue(market.authenticated());market.endpoint("https://other.example.org","");assertFalse(market.authenticated());assertThrows(IllegalStateException.class,market::token);}
    @Test void expiryIsEnforcedBeforeAnyAuthenticatedRequest(){var market=new MarketClient();market.authenticate(JsonParser.parseString("{\"token\":\"expired\",\"uuid\":\"id\",\"username\":\"name\",\"expiresAt\":1}").getAsJsonObject());assertFalse(market.authenticated());assertThrows(IllegalStateException.class,market::token);}
    @Test void pathsAndSearchTermsCannotAlterEndpointQueries(){assertEquals("pack%2Fid%3Fadmin%3Dtrue",MarketClient.path("pack/id?admin=true"));assertEquals("a%20b",MarketClient.path("a b"));}
    @Test void automaticSignInDeduplicatesAndRetainsFailureUntilRetry(){
        var market=new MarketClient();market.endpoint("https://example.org","");var calls=new AtomicInteger();var pending=new CompletableFuture<JsonObject>();
        Supplier<CompletableFuture<JsonObject>> signIn=()->{calls.incrementAndGet();return pending;};
        var first=market.signIn("connection:profile",false,signIn);assertSame(first,market.signIn("connection:profile",false,signIn));assertEquals(1,calls.get());assertEquals("signing_in",market.authenticationStatus());
        pending.completeExceptionally(new IllegalStateException("Session rejected"));assertEquals("failed",market.authenticationStatus());assertSame(first,market.signIn("connection:profile",false,signIn));assertEquals(1,calls.get());
        market.signIn("connection:profile",true,()->{calls.incrementAndGet();return CompletableFuture.failedFuture(new IllegalStateException("Retry rejected"));});assertEquals(2,calls.get());
        market.signIn("new connection:profile",false,()->{calls.incrementAndGet();return CompletableFuture.failedFuture(new IllegalStateException("New connection rejected"));});assertEquals(3,calls.get());
    }
    @Test void successfulSignInCommitsAValidatedSessionAndReturnsItsFullReply(){
        var market=configured();var source=new CompletableFuture<JsonObject>();String identity="0:"+ACCOUNT;
        var pending=market.signIn(identity,false,()->source);
        market.stage(identity,"mojang");assertEquals("mojang",market.authenticationStage());
        source.complete(session(ACCOUNT));assertEquals(TOKEN,pending.join().get("token").getAsString());
        assertTrue(market.authenticated());assertEquals("signed_in",market.authenticationStatus());
        assertEquals(ACCOUNT,market.sessionEntry().orElseThrow().userId());
        assertEquals(TOKEN,market.signIn(identity,false,()->{fail("A current account should reuse its marketplace session");return null;}).join().get("token").getAsString());
    }
    @Test void signInTimesOutCancelsItsSourceAndIgnoresALateReply() throws Exception {
        var market=new MarketClient(Duration.ofMillis(50));market.endpoint("https://example.org","");
        var source=new UncancellableReply();String identity="0:"+ACCOUNT;
        var pending=market.signIn(identity,false,()->source);
        var failed=assertThrows(ExecutionException.class,()->pending.get(2,TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class,failed.getCause());
        assertEquals("failed",market.authenticationStatus());assertTrue(market.authenticationError().contains("timed out"));
        source.complete(session(ACCOUNT));assertFalse(market.authenticated());
        market.stage(identity,"verify");assertEquals("failed",market.authenticationStage());
        var retried=market.signIn(identity,true,()->CompletableFuture.completedFuture(session(ACCOUNT)));
        assertEquals(TOKEN,retried.get(2,TimeUnit.SECONDS).get("token").getAsString());assertTrue(market.authenticated());
    }
    @Test void deadlineCancelsAnInterruptibleAuthenticationFuture() throws Exception {
        var market=new MarketClient(Duration.ofMillis(50));market.endpoint("https://example.org","");var source=new CompletableFuture<JsonObject>();
        var result=market.signIn("0:"+ACCOUNT,false,()->source);
        assertThrows(ExecutionException.class,()->result.get(2,TimeUnit.SECONDS));
        source.handle((reply,error)->null).get(2,TimeUnit.SECONDS);
        assertTrue(source.isCancelled());assertFalse(market.authenticated());
    }
    @Test void switchingEndpointsDiscardsThePendingProofAndAnyLateResponse(){
        var market=configured();var source=new UncancellableReply();var result=market.signIn("0:"+ACCOUNT,false,()->source);
        market.endpoint("https://other.example.org","");assertTrue(result.isCancelled());
        source.complete(session(ACCOUNT));assertFalse(market.authenticated());assertEquals("https://other.example.org",market.endpoint());
        assertEquals("",market.certificateSha256());assertEquals("guest",market.authenticationStatus());
    }
    @Test void switchingAccountsCannotCoalesceOrCommitThePreviousAccountsReply(){
        var market=configured();var oldSource=new UncancellableReply();var currentSource=new CompletableFuture<JsonObject>();
        var old=market.signIn("0:"+ACCOUNT,false,()->oldSource);
        var current=market.signIn("0:"+OTHER_ACCOUNT,false,()->currentSource);assertNotSame(old,current);assertTrue(old.isCancelled());
        market.stage("0:"+ACCOUNT,"verify");assertEquals("challenge",market.authenticationStage());
        oldSource.complete(session(ACCOUNT));assertFalse(market.authenticated());
        currentSource.complete(session(OTHER_ACCOUNT));current.join();assertEquals(OTHER_ACCOUNT,market.userId());
        var next=market.signIn("0:"+ACCOUNT,false,()->CompletableFuture.completedFuture(session(ACCOUNT)));next.join();assertEquals(ACCOUNT,market.userId());
    }
    @Test void missingTokensAndAnIdentityMismatchNeverAuthenticateTheAccount(){
        var market=configured();var missing=session(ACCOUNT);missing.remove("token");
        assertThrows(CompletionException.class,()->market.signIn("0:"+ACCOUNT,false,()->CompletableFuture.completedFuture(missing)).join());assertFalse(market.authenticated());
        assertThrows(CompletionException.class,()->market.signIn("0:"+ACCOUNT,true,()->CompletableFuture.completedFuture(session(OTHER_ACCOUNT))).join());assertFalse(market.authenticated());
    }
    @Test void clearingAuthenticationCancelsTheAttemptWithoutAllowingItsLaterSuccess(){
        var market=configured();var source=new UncancellableReply();var result=market.signIn("0:"+ACCOUNT,false,()->source);
        market.clearAuthentication();assertTrue(result.isCancelled());source.complete(session(ACCOUNT));assertFalse(market.authenticated());
        assertEquals("guest",market.authenticationStatus());assertEquals("",market.authenticationError());
    }
    @Test void cancellingTheReturnedFuturePreventsItsSourceFromSigningInLater(){
        var market=configured();var source=new UncancellableReply();var result=market.signIn("0:"+ACCOUNT,false,()->source);
        result.cancel(false);source.complete(session(ACCOUNT));assertFalse(market.authenticated());assertEquals("failed",market.authenticationStatus());
    }
    @Test void transientClassificationPreservesNetworkFailuresAndRejectsCredentialAndTlsFailures(){
        assertTrue(MarketClient.isTransient(new CompletionException(new IOException("Connection reset"))));
        assertTrue(MarketClient.isTransient(new TimeoutException("Timed out")));
        assertTrue(MarketClient.isTransient(new MarketClient.RequestException(503,"mojang_unavailable","Try later")));
        assertTrue(MarketClient.isTransient(new MarketClient.RequestException(401,"join_not_verified","Try later")));
        assertFalse(MarketClient.isTransient(new MarketClient.RequestException(401,"invalid_token","Invalid account")));
        assertFalse(MarketClient.isTransient(new CompletionException(new SSLHandshakeException("Certificate does not match its configured pin"))));
        assertTrue(MarketClient.isTransient(new AuthenticationUnavailableException("Official session service is unavailable")));
        assertFalse(MarketClient.isTransient(new InvalidCredentialsException("Session expired",new IOException("Nested transport detail"))));
    }
    @Test void responseErrorsRetainStatusAndCodeEvenWhenAProxyDoesNotReturnJson(){
        var market=configured();var failure=assertThrows(CompletionException.class,()->market.handleResponse(503,"<html>upstream unavailable</html>",market.generation(),"",false));
        var error=assertInstanceOf(MarketClient.RequestException.class,failure.getCause());assertEquals(503,error.statusCode());assertEquals("http_error",error.code());assertTrue(MarketClient.isTransient(error));
    }
    @Test void rejectedCurrentTokensAreClearedButOldResponsesCannotClearANewSession(){
        var market=configured();market.authenticate(session(ACCOUNT));String rejected="{\"error\":{\"code\":\"invalid_token\",\"message\":\"Session expired\"}}";
        assertThrows(CompletionException.class,()->market.handleResponse(401,rejected,market.generation(),TOKEN,true));assertFalse(market.authenticated());
        market.authenticate(session(ACCOUNT));assertThrows(CompletionException.class,()->market.handleResponse(401,rejected,market.generation(),"older-market-token",true));assertTrue(market.authenticated());
        var oldGeneration=market.generation();market.endpoint("https://other.example.org","");market.authenticate(session(ACCOUNT));
        assertThrows(CompletionException.class,()->market.handleResponse(401,rejected,oldGeneration,TOKEN,true));assertTrue(market.authenticated());
        String denied="{\"error\":{\"code\":\"permission_denied\",\"message\":\"Permission required\"}}";
        assertThrows(CompletionException.class,()->market.handleResponse(403,denied,market.generation(),TOKEN,true));assertTrue(market.authenticated());
    }
    @Test void changingEndpointDuringLockedSourceCompletionCannotInvertTheClientAndSourceMonitors() throws Exception {
        var market=configured();var holdingSource=new CountDownLatch(1);var cancellationEntered=new CountDownLatch(1);
        var source=new CompletableFuture<JsonObject>() {
            @Override public boolean cancel(boolean interrupt){
                cancellationEntered.countDown();
                synchronized(this){return super.cancel(interrupt);}
            }
        };
        var result=market.signIn("0:"+ACCOUNT,false,()->source);
        var executor=Executors.newFixedThreadPool(2,task->{var thread=new Thread(task,"market-client-lock-test");thread.setDaemon(true);return thread;});
        try{
            var completing=executor.submit(()->{
                synchronized(source){
                    holdingSource.countDown();
                    assertTrue(cancellationEntered.await(2,TimeUnit.SECONDS));
                    source.complete(session(ACCOUNT));
                }
                return null;
            });
            assertTrue(holdingSource.await(2,TimeUnit.SECONDS));
            var resetting=executor.submit(()->market.endpoint("https://other.example.org",""));
            resetting.get(2,TimeUnit.SECONDS);completing.get(2,TimeUnit.SECONDS);
            assertTrue(result.isCancelled());assertFalse(market.authenticated());assertEquals("https://other.example.org",market.endpoint());
        }finally{cancellationEntered.countDown();executor.shutdownNow();}
    }
}
