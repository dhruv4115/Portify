package com.protify.portfolio.security.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Test-only support for D1-B1: builds Google-shaped ID tokens signed with a locally-generated
 * RSA key pair. No test in this project ever calls Google.
 */
public final class TestJwtSupport {

    public static final String ISSUER = "https://accounts.google.com";
    public static final String CLIENT_ID = "test-client-id.apps.googleusercontent.com";

    private final RSAKey signingKey;
    public final RSAPublicKey publicKey;

    public TestJwtSupport() {
        this.signingKey = generateKey();
        try {
            this.publicKey = signingKey.toRSAPublicKey();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive test RSA public key", e);
        }
    }

    public String validToken(String subject, boolean emailVerified) {
        return token(signingKey, ISSUER, CLIENT_ID, subject, emailVerified,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
    }

    public String tokenSignedWithWrongKey(String subject) {
        return token(generateKey(), ISSUER, CLIENT_ID, subject, true,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
    }

    public String tokenWithWrongAudience(String subject) {
        return token(signingKey, ISSUER, "someone-elses-client-id.apps.googleusercontent.com", subject, true,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
    }

    public String tokenWithWrongIssuer(String subject) {
        return token(signingKey, "https://not-google.example.com", CLIENT_ID, subject, true,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
    }

    public String expiredToken(String subject) {
        return token(signingKey, ISSUER, CLIENT_ID, subject, true,
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
    }

    private static String token(RSAKey key, String issuer, String audience, String subject,
                                 boolean emailVerified, Instant issuedAt, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .claim("email", subject + "@example.com")
                    .claim("email_verified", emailVerified)
                    .claim("name", "Test User")
                    .claim("picture", "https://example.com/pic.jpg")
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .jwtID(UUID.randomUUID().toString())
                    .build();

            SignedJWT signedJwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            signedJwt.sign(new RSASSASigner(key));
            return signedJwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build test JWT", e);
        }
    }

    private static RSAKey generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate test RSA key", e);
        }
    }
}
