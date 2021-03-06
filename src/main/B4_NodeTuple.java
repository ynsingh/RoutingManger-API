package main;

import java.security.PublicKey;

/**
 * This class will is used to create an Object which contains a tuple- NodeID,PublicKey and HashID
 * This class is used along with main.resources.B4_Node to create a bigger Object used in the RoutingTable and NeighbourTable.
 */
public class B4_NodeTuple {
    private final String nodeID;
    private final PublicKey publicKey;
    private final String hashID;

    /**
     * @param nodeID
     * @param publicKey
     * @param hashID
     */
    public B4_NodeTuple(String nodeID, PublicKey publicKey,String hashID) {
        this.nodeID = nodeID;
        this.publicKey = publicKey;
        this.hashID=hashID;
    }

    /**
     * @return
     */
    public String getNodeID() {
        return nodeID;
    }

    /**
     * @return
     */
    public PublicKey getPublicKey() {
        return publicKey;
    }

    /**
     * @return
     */
    public String getHashID(){return hashID;}

}
