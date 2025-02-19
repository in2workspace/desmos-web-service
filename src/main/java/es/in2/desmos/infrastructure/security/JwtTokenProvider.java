package es.in2.desmos.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.bc.BouncyCastleProviderSingleton;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.InvalidKeySpecException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    private final ECKey ecJWK;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    // Build a useful Private Key from the hexadecimal private key set in the application.properties
    public JwtTokenProvider(SecurityProperties securityProperties) throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeySpecException {
        Security.addProvider(BouncyCastleProviderSingleton.getInstance());

        String privateKeyHex = securityProperties.privateKey().replace("0x", "");

        // Convert the private key from hexadecimal to BigInteger
        BigInteger privateKeyInt = new BigInteger(privateKeyHex, 16);

        // Get the curve parameters for secp256r1
        org.bouncycastle.jce.spec.ECParameterSpec bcEcSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
        ECCurve bcCurve = bcEcSpec.getCurve();
        EllipticCurve curve = new EllipticCurve(
                new java.security.spec.ECFieldFp(bcCurve.getField().getCharacteristic()),
                bcCurve.getA().toBigInteger(),
                bcCurve.getB().toBigInteger()
        );
        ECPoint bcG = bcEcSpec.getG();
        java.security.spec.ECPoint g = new java.security.spec.ECPoint(bcG.getAffineXCoord().toBigInteger(), bcG.getAffineYCoord().toBigInteger());

        // Convert the cofactor to int
        int h = bcEcSpec.getH().intValue();
        java.security.spec.ECParameterSpec ecSpec = new java.security.spec.ECParameterSpec(curve, g, bcEcSpec.getN(), h);

        KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
        ECPrivateKey privateKey = (ECPrivateKey) keyFactory.generatePrivate(new ECPrivateKeySpec(privateKeyInt, bcEcSpec));

        // Obtain the public point Q by multiplying the generator G by the private key
        ECPoint q = bcEcSpec.getG().multiply(privateKeyInt);
        q = q.normalize(); // Normalize the public point
        java.security.spec.ECPoint w = new java.security.spec.ECPoint(q.getAffineXCoord().toBigInteger(), q.getAffineYCoord().toBigInteger());

        // Generate the public key from the public point
        ECPublicKey ecPublicKey = (ECPublicKey) keyFactory.generatePublic(new ECPublicKeySpec(w, ecSpec));
        this.ecJWK = new ECKey.Builder(Curve.P_256, ecPublicKey)  // Changed to P-256
                .privateKey(privateKey)
                .keyUse(KeyUse.SIGNATURE)
                .build();
    }

    // Generate JWT + SEP256R1 signature
    // https://connect2id.com/products/nimbus-jose-jwt/examples/jwt-with-es256-signature
    public String generateToken(String resourceURI) throws JOSEException {

        // Sample JWT claims
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .claim("htm", HttpMethod.POST.name())
                .claim("htu", resourceURI)
                .issueTime(new Date())
                .build();
        // Import new Provider to work with ECDSA and Java 17
        ECDSASigner signer = new ECDSASigner(ecJWK);
        signer.getJCAContext().setProvider(BouncyCastleProviderSingleton.getInstance());

        // Create JWT for the ES256 algorithm (P-256)
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)  // Changed to ES256
                        .type(new JOSEObjectType("dpop+jwt"))
                        .jwk(ecJWK.toPublicJWK())
                        .build(),
                claimsSet);

        // Sign with a private EC key
        jwt.sign(signer);
        return jwt.serialize();
    }

    // Use public keys from Access Node Directory in memory
    public Mono<SignedJWT> validateSignedJwt(String jwtString, String externalNodeUrl, Map<String, String> publicKeysByUrl) {
        try {
            // Retrieve the public key from AccessNodeMemoryStore
            String publicKeyHex = getPublicKeyFromAccessNodeMemory(externalNodeUrl, publicKeysByUrl);
            if (publicKeyHex == null) {
                return Mono.error(new InvalidKeyException("Public key not found for origin: " + externalNodeUrl));
            }

            // Convert the hexadecimal public key to ECPublicKey
            ECPublicKey ecPublicKey = convertHexPublicKeyToECPublicKey(publicKeyHex);

            ECDSAVerifier verifier = new ECDSAVerifier(ecPublicKey);
            verifier.getJCAContext().setProvider(BouncyCastleProviderSingleton.getInstance());

            SignedJWT jwt = SignedJWT.parse(jwtString);
            boolean verified = jwt.verify(verifier);
            if (verified) {
                log.info("VERIFIED OK? {}", jwt.verify(verifier));
                return Mono.just(jwt);
            } else {
                return Mono.error(new Exception("JWT verification failed"));
            }
        } catch (Exception e) {
            log.warn("Error parsing JWT", e);
            return Mono.error(new InvalidKeyException());
        }
    }

    private String getPublicKeyFromAccessNodeMemory(String origin, Map<String, String> publicKeysByUrl) {
        log.info("JwtTokenProvider -- Init -- getPublicKeyFromAccessNodeMemory()");

        if (publicKeysByUrl == null || publicKeysByUrl.isEmpty()) {
            log.warn("No organizations data available in AccessNodeMemoryStore.");
            return null;
        } else {
            var publicKey = publicKeysByUrl.get(origin);

            if (publicKey == null) {
                log.warn("Public key not found for origin: {}", origin);
            }

            return publicKey;
        }
    }

    public ECPublicKey convertHexPublicKeyToECPublicKey(String hexPublicKey)
            throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeySpecException {

        String replacedPublicKeyHex = hexPublicKey.replace("0x", "");

        // Convert the public key from hexadecimal to BigInteger
        BigInteger publicKeyInt = new BigInteger(replacedPublicKeyHex, 16);

        // Get the curve specification for secp256r1
        ECParameterSpec bcEcSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
        ECCurve bcCurve = bcEcSpec.getCurve();

        // Convert BigInteger to ECPoint (this is a raw public key)
        ECPoint q = bcCurve.decodePoint(publicKeyInt.toByteArray());
        q = q.normalize();  // Ensure the point is normalized

        // Convert BouncyCastle ECPoint to standard ECPoint
        java.security.spec.ECPoint w = new java.security.spec.ECPoint(
                q.getAffineXCoord().toBigInteger(),
                q.getAffineYCoord().toBigInteger()
        );

        // Construct the EllipticCurve and ECParameterSpec using Java's standard classes
        EllipticCurve curve = new EllipticCurve(
                new ECFieldFp(bcCurve.getField().getCharacteristic()),
                bcCurve.getA().toBigInteger(),
                bcCurve.getB().toBigInteger()
        );

        java.security.spec.ECParameterSpec ecSpec = new java.security.spec.ECParameterSpec(
                curve,
                new java.security.spec.ECPoint(
                        bcEcSpec.getG().getAffineXCoord().toBigInteger(),
                        bcEcSpec.getG().getAffineYCoord().toBigInteger()
                ),
                bcEcSpec.getN(),
                bcEcSpec.getH().intValue()
        );

        // Create the public key from the ECPoint and specification
        KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
        ECPublicKeySpec ecPublicKeySpec = new ECPublicKeySpec(w, ecSpec);
        return (ECPublicKey) keyFactory.generatePublic(ecPublicKeySpec);
    }
}