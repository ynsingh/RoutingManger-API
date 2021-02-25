package main;

import org.apache.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.*;

/**
 * This class is used to generate NodeID from the public key generated using the NodeCryptography class.
 * It also generate hashID which is generated by signing NodeID with the private key of the current node.
 * hashID generated can be verified using the verifySignature method.This method can access from the main.resources.RoutingManager class .
 */
class B4_NodeGeneration {
    private static final Logger log = Logger.getLogger(B4_NodeGeneration.class);
    private final String nodeID;
    private final PublicKey publicKey;
    private final String hashID;
    private NodeCryptography nodeCryptography;
    private byte[] signatureData;

    B4_NodeGeneration() {
        nodeCryptography = NodeCryptography.getInstance();
        publicKey = nodeCryptography.getPublicKey();
        nodeID = generateNodeId();
        hashID = signNodeIdUsingPrivateKey();
    }

    B4_NodeGeneration(String nodeID, PublicKey publicKey, String hashID) {
        this.nodeID = nodeID;
        this.publicKey = publicKey;
        this.hashID = hashID;
    }

    /**
     * @return - It will generate nodeID for the Node.
     * Return type is String
     */
    private String generateNodeId() {
        String node1ID=null;
        StringBuilder publicKeyToString = new StringBuilder();
        for (byte bytes : publicKey.getEncoded()) {
            publicKeyToString.append(String.format("%02x", bytes).toUpperCase());
        }
        byte[] messageByte = publicKeyToString.toString().getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
            messageDigest.update(messageByte);
            byte[] digest = messageDigest.digest();

            // Converting byte[] to HexString format
            StringBuilder hexString = new StringBuilder();
            for (byte bytes : digest) {
                hexString.append(String.format("%02x", bytes).toUpperCase());
            }
            node1ID = hexString.toString();
            log.info("Node ID is generated Successfully");
            log.info("Node ID -"+node1ID);
        } catch (NoSuchAlgorithmException e) {
           log.error("Exception Occurred",e);
        }
        return node1ID;
    }

    /**
     * @return - hashId obtained by signing the NodeID by private key
     */
    private String signNodeIdUsingPrivateKey() {
        String hash1ID = null;
        StringBuilder signData = new StringBuilder();
        byte[] data = getNodeID().getBytes(StandardCharsets.UTF_8);
        try {
            Signature signature = Signature.getInstance("SHA1WithRSA");
            signature.initSign(nodeCryptography.getFromKeyStore());
            signature.update(data);
            signatureData = signature.sign();
            for (byte bytes : signatureData) {
                signData.append(String.format("%02x", bytes).toUpperCase());
            }
            log.info("NodeID signed using PrivateKey");
            hash1ID = signData.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            log.error("Exception Occurred",e);
        }
        return hash1ID;
    }

     boolean verifySignature() {
        boolean verify = false;
        byte[] data = getNodeID().getBytes(StandardCharsets.UTF_8);
        try {
            Signature signature = Signature.getInstance("SHA1WithRSA");
            System.out.println(nodeCryptography.pubToStr(publicKey));
            signature.initVerify(publicKey);
            signature.update(data);
            verify = signature.verify(signatureData);
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            log.error("Exception Occurred",e);
        }
        return verify;
    }

    String getNodeID() {
        return nodeID;
    }

    PublicKey getPublicKey() {
        return publicKey;
    }

    String getHashID() {
        return hashID;
    }

    public static void main(String[] args) {
        B4_NodeGeneration nodeGeneration = new B4_NodeGeneration();
        System.out.println(nodeGeneration.verifySignature());

    }
}