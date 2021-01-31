import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.InetAddress;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by S/L Umesh U Nair
 * <br>Aim is to create an Routing Manager API for Brihaspati-4
 * <br> Various layers presently implemented
 * <br> 1. BaseRoutingTable - LayerID = 0
 * <br> 2. StorageRoutingTable - LayerID = 1
 */
public class RoutingManager {
    private static RoutingManager routingManager;
    private static RoutingManagerBuffer routingManagerBuffer;
    private static ConfigData config;
    private final B4_Node[][] localBaseRoutingTable;
    private final B4_Node[] localBaseNeighbourTable;
    private final B4_Node[][] storageRoutingTable;
    private final B4_Node[] storageNeighbourTable;
    private B4_Node localNode;
    private final NodeCryptography nodeCryptography;
    private B4_NodeGeneration b4_nodeGeneration;
    private final int rt_dimension;
    private final int nt_dimension;
    private final long incrementTime;
    private final long sleepTime;
    private String selfIPAddress;
    private String selfTransportAddress;
    private String selfPortAddress;

    /**
     * Constructor
     * <br>Main job of constructor are as follows:-
     * <br>Check routing table and neighbour table exist from the previous login(ie to check RoutingTable.xml is available in the path).
     * <br>If RT exists then data is taken from the xml file and added to the localBaseRoutingTable(which is the routingTable for current node)
     * and to the localBaseNeighbourTable(which is the neighbourTable for current node).
     * <br>If not available then create a routing table(localBaseRoutingTable) and neighbour table (localBaseNeighbourTable).
     * <br>Initial entries of localBaseRoutingTable and localBaseNeighbourTable should be object of B4_Node with only bootstrap node entry.
     */
    private RoutingManager() {
        nodeCryptography = NodeCryptography.getInstance();
        routingManagerBuffer = RoutingManagerBuffer.getInstance();
        boolean nodeDetailsExists;
        File nodeFile = new File("NodeDetails.txt");
        nodeDetailsExists = nodeFile.exists();

        if (!nodeDetailsExists) {
            b4_nodeGeneration = new B4_NodeGeneration();
            try {
                FileWriter writer = new FileWriter("NodeDetails.txt");
                PrintWriter printWriter = new PrintWriter(writer);
                printWriter.println("#  Self Node Details  #");
                printWriter.println("..................................");
                printWriter.println("NodeID=" + b4_nodeGeneration.getNodeID());
                printWriter.println("PublicKey=" + nodeCryptography.pubToStr(b4_nodeGeneration.getPublicKey()));
                printWriter.println("HashID=" + b4_nodeGeneration.getHashID());
                printWriter.println("IPAddress=191.126.10.12");
                printWriter.println("PortAddress=1024");
                printWriter.println("TransportAddress=TCP");
                printWriter.close();
                selfIPAddress = "191.126.10.12";
                selfPortAddress = "1024";
                selfTransportAddress = "TCP";
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try {
                FileReader reader = new FileReader("NodeDetails.txt");
                Properties properties = new Properties();
                properties.load(reader);
                String selfNodeID = properties.getProperty("NodeID");
                String selfPublicKey = properties.getProperty("PublicKey");
                String selfHashID = properties.getProperty("HashID");
                selfIPAddress = properties.getProperty("IPAddress");
                selfPortAddress = properties.getProperty("PortAddress");
                selfTransportAddress = properties.getProperty("TransportAddress");
                //System.out.println(selfIPAddress+"   "+selfPortAddress+"   "+selfTransportAddress+"   "+selfNodeID);
                b4_nodeGeneration = new B4_NodeGeneration(selfNodeID, nodeCryptography.strToPub(selfPublicKey), selfHashID);
            } catch (IOException e) {
                System.out.println("NodeDetails File not Found or Issue in file fetching");
            }
        }
        config = ConfigData.getInstance();
        rt_dimension = config.getRoutingTableLength();
        nt_dimension = config.getNeighbourTableLength();
        incrementTime = config.getIncrementTime();
        sleepTime = config.getSleepTime();
        setLocalNode();
        localBaseRoutingTable = new B4_Node[rt_dimension][3];
        localBaseNeighbourTable = new B4_Node[nt_dimension];
        storageRoutingTable = new B4_Node[rt_dimension][3];
        storageNeighbourTable = new B4_Node[nt_dimension];
        init("BaseRoutingTable", localBaseRoutingTable, localBaseNeighbourTable);
        boolean access = config.isLayerAccess("StorageAccess");
        if (access) init("StorageRoutingTable", storageRoutingTable, storageNeighbourTable);
        fetchFileFromInputBuffer();
    }

    /**
     * @param rtFileName     - Desired name of the Routing Table
     * @param routingTable   - Object of Routing Table
     * @param neighbourTable - Object of Neighbour Table
     *                       <br>All the initialisation w.r.t routing Manager will be performed here.
     *                       <br>This function is called by the constructor for initialisation of routing manager.
     *                       <br>Initialisation includes creating routingTable and neighbour table,creating a routing table file for future references etc.
     */
    private void init(String rtFileName, B4_Node[][] routingTable, B4_Node[] neighbourTable) {
        boolean rtExists;
        File rtFile = new File(rtFileName + ".xml");
        rtExists = rtFile.exists();

        if (!rtExists) {

            /* Routing Table */
            for (int i = 0; i < rt_dimension; i++) {
                for (int j = 0; j < 3; j++) {
                    routingTable[i][j] = new B4_Node(new B4_NodeTuple("", null, ""), "", "", "");
                }
            }
            B4_Node bootStrapNode = config.getBootStrapNode();
            mergerRT(bootStrapNode, routingTable);

            /* Neighbour Table */
            for (int i = 0; i < nt_dimension; i++) {
                neighbourTable[i] = new B4_Node(new B4_NodeTuple("", null, ""), "", "", "", -1);
            }
            localBaseTablesToXML(rtFileName, routingTable, neighbourTable);

        } else {
            try {
                DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder documentBuilder = builderFactory.newDocumentBuilder();

                Document doc = documentBuilder.parse(new File(rtFileName + ".xml"));
                doc.getDocumentElement().normalize();
                //String rootElement = doc.getDocumentElement().getNodeName();

                NodeList nodeList = doc.getElementsByTagName("B4_Node");
                for (int i = 0; i < nodeList.getLength(); i++) {
                    Node node = nodeList.item(i);
                    if (node.getNodeType() == node.ELEMENT_NODE) {
                        Element element = (Element) node;

                        //Get the value of ID attribute
                        String index = node.getAttributes().getNamedItem("INDEX").getNodeValue();

                        //Get value of all sub-Elements
                        String nodeID = element.getElementsByTagName("NODEID").item(0).getTextContent();
                        String nodePub = element.getElementsByTagName("PUBLICKEY").item(0).getTextContent();
                        String nodeHash = element.getElementsByTagName("HASHID").item(0).getTextContent();
                        String nodeIP = element.getElementsByTagName("NODEIP").item(0).getTextContent();
                        String nodePort = element.getElementsByTagName("NODEPORT").item(0).getTextContent();
                        String nodeTransport = element.getElementsByTagName("NODETRANSPORT").item(0).getTextContent();

                        Pattern pattern = Pattern.compile("\\[([^]]+)\\]");
                        Matcher matcher = pattern.matcher(index);
                        matcher.find();
                        int index1 = Integer.parseInt(matcher.group(1));
                        matcher.find();
                        int index2 = Integer.parseInt(matcher.group(1));
                        routingTable[index1][index2] = new B4_Node(new B4_NodeTuple(nodeID, nodeCryptography.strToPub(nodePub), nodeHash), nodeIP, nodePort, nodeTransport);
                    }
                }
                NodeList nodeList1 = doc.getElementsByTagName("NEIGHBOUR");
                for (int i = 0; i < nodeList1.getLength(); i++) {
                    Node node = nodeList1.item(i);

                    if (node.getNodeType() == node.ELEMENT_NODE) {
                        Element element = (Element) node;
                        String index = node.getAttributes().getNamedItem("INDEX").getNodeValue();

                        //Get value of all sub-Elements
                        String nodeID = element.getElementsByTagName("NODEID").item(0).getTextContent();
                        String nodePub = element.getElementsByTagName("PUBLICKEY").item(0).getTextContent();
                        String nodeHash = element.getElementsByTagName("HASHID").item(0).getTextContent();
                        String nodeIP = element.getElementsByTagName("NODEIP").item(0).getTextContent();
                        String nodePort = element.getElementsByTagName("NODEPORT").item(0).getTextContent();
                        String nodeTransport = element.getElementsByTagName("NODETRANSPORT").item(0).getTextContent();
                        String nodeRTT = element.getElementsByTagName("NODERTT").item(0).getTextContent();

                        Pattern pattern = Pattern.compile("\\[([^]]+)\\]");
                        Matcher matcher = pattern.matcher(index);
                        matcher.find();
                        int index1 = Integer.parseInt(matcher.group(1));
                        neighbourTable[index1] = new B4_Node(new B4_NodeTuple(nodeID, nodeCryptography.strToPub(nodePub), nodeHash), nodeIP, nodePort, nodeTransport, Float.parseFloat(nodeRTT));
                    }
                }
            } catch (ParserConfigurationException | IOException | SAXException | NullPointerException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * @return RoutingManger Object
     * <br>This method is required to create an instance of RoutingManager.
     * <br>Instance of RoutingManager will be obtained by calling this function.
     */
    public static synchronized RoutingManager getInstance() {
        if (routingManager == null) {
            routingManager = new RoutingManager();
        }
        return routingManager;
    }

    /**
     * @return B4_Node Object
     * <br>This method is used for getting Local Node Information.
     * <br>This method can be called by any function to get complete information about the current Node.
     */
    public B4_Node getLocalNode() {
        return localNode;
    }

    /**
     * @param fileFromBuffer - The file that is fetched from the input buffer of the routing Table.
     * @param layerID        - Specify the layer Id of the routing table which needs to be merged
     *                       <br>This method is used for merging routing table obtained from other B4_Node in to localBaseRoutingTable.
     *                       <br>Merging is performed by one by one comparing of nodeID obtained from the received node with existing nodeID in the localBaseRoutingTable.
     *                       <br>Initial merging of localBaseRoutingTable happens with the routing Table obtained from the Bootstrap Node.
     *                       <br>Nibble wise comparison is done(b/w mergerTableNodeId and localNodeID) to obtain the column in localBaseRoutingTable
     *                       Array at which the data is to be updated.
     *                       <br>Based on the algorithm the B4_Node will be place in the predecessor ,successor or middle row of the obtained column.
     */
    public void mergeRoutingTable(File fileFromBuffer, int layerID) {

        B4_Node[][] routingTableLayer = null;
        if (layerID == 0) {
            routingTableLayer = localBaseRoutingTable;
        } else if (layerID == 1) {
            routingTableLayer = storageRoutingTable;
        }
        B4_Node selfNodeOfMergerTable = getSelfNodeOfMergerTable(fileFromBuffer.getAbsolutePath());
        B4_Node[][] mergerRoutingTable = getMergerRoutingTable(fileFromBuffer.getAbsolutePath());

        mergerRT(selfNodeOfMergerTable, routingTableLayer);
        for (int i = 0; i < rt_dimension; i++) {
            for (int j = 0; j < 3; j++) {
                mergerRT(mergerRoutingTable[i][j], routingTableLayer);
            }
        }
        if (routingTableLayer == localBaseRoutingTable) {
            localBaseTablesToXML("BaseRoutingTable", localBaseRoutingTable, localBaseNeighbourTable);
            System.out.println("BaseRoutingTable Merging completed Successfully");
        }
        if (routingTableLayer == storageRoutingTable) {
            localBaseTablesToXML("StorageRoutingTable", storageRoutingTable, storageNeighbourTable);
            System.out.println("StorageRoutingTable Merging completed Successfully");
        }
    }

    /**
     * @param fileFromBuffer - File fetched from the inputbuffer of routing Table
     * @param layerID - The layer in which the operation is to be performed
     */
    public void mergeNeighbourTable(File fileFromBuffer, int layerID) {
        B4_Node[] neighbourTable = null;
        if (layerID == 0) {
            neighbourTable = localBaseNeighbourTable;
        } else if (layerID == 1) {
            neighbourTable = storageNeighbourTable;
        }
        boolean rttFileExists;
        B4_Node[] mergerNeighbourTable = new B4_Node[nt_dimension];
        B4_Node selfMergerNode = null;
        B4_Node selfNodeOfMergerTable = getSelfNodeOfMergerTable(fileFromBuffer.getAbsolutePath());
        String mergerNodeID = selfNodeOfMergerTable.getB4node().getNodeID();
//        String fileName = "RcvRTT_" + layerID + "_" + mergerNodeID;
        File rttFile = new File(fileFromBuffer.getName());
        rttFileExists = rttFile.exists();
        if (!rttFileExists) {
            System.out.println("RTT updated file does not exist");
        } else {
            //Get Document builder
            DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder;
            try {
                documentBuilder = builderFactory.newDocumentBuilder();
                //Load the input XML document,parse it and return an instance of Document class
                Document doc = documentBuilder.parse(new File(fileFromBuffer.getName()));
                doc.getDocumentElement().normalize();
                //String rootElement = doc.getDocumentElement().getNodeName();
                //System.out.println(rootElement);
                String selfNodeID = doc.getDocumentElement().getAttribute("SELF_NODE_ID");
                String selfNodePub = doc.getDocumentElement().getAttribute("SELF_PUBLIC_KEY");
                String selfNodeHash = doc.getDocumentElement().getAttribute("SELF_HASH_ID");
                String selfIPAddress = doc.getDocumentElement().getAttribute("SELF_IP_ADDRESS");
                String selfPortAddress = doc.getDocumentElement().getAttribute("SELF_PORT_ADDRESS");
                String selfTransport = doc.getDocumentElement().getAttribute("SELF_TRANSPORT");
                String selfRTT = doc.getDocumentElement().getAttribute("SELF_RTT");
                selfMergerNode = new B4_Node(new B4_NodeTuple(selfNodeID, nodeCryptography.strToPub(selfNodePub), selfNodeHash), selfIPAddress, selfPortAddress, selfTransport, Float.parseFloat(selfRTT));

                NodeList nodeList = doc.getElementsByTagName("NEIGHBOUR");
                for (int i = 0; i < nodeList.getLength(); i++) {
                    Node node = nodeList.item(i);

                    if (node.getNodeType() == node.ELEMENT_NODE) {
                        Element element = (Element) node;

                        //Get the value of ID attribute
                        String index = node.getAttributes().getNamedItem("INDEX").getNodeValue();

                        //Get value of all sub-Elements
                        String nodeID = element.getElementsByTagName("NODEID").item(0).getTextContent();
                        String nodePub = element.getElementsByTagName("PUBLICKEY").item(0).getTextContent();
                        String nodeHash = element.getElementsByTagName("HASHID").item(0).getTextContent();
                        String nodeIP = element.getElementsByTagName("NODEIP").item(0).getTextContent();
                        String nodePort = element.getElementsByTagName("NODEPORT").item(0).getTextContent();
                        String nodeTransport = element.getElementsByTagName("NODETRANSPORT").item(0).getTextContent();
                        String nodeRTT = element.getElementsByTagName("NODERTT").item(0).getTextContent();
                        Pattern pattern = Pattern.compile("\\[([^]]+)\\]");
                        Matcher matcher = pattern.matcher(index);
                        matcher.find();
                        int index1 = Integer.parseInt(matcher.group(1));
                        mergerNeighbourTable[index1] = new B4_Node(new B4_NodeTuple(nodeID, nodeCryptography.strToPub(nodePub), nodeHash), nodeIP, nodePort, nodeTransport, Float.parseFloat(nodeRTT));
                    }
                }
            } catch (ParserConfigurationException | SAXException | IOException e) {
                e.printStackTrace();
            }
            for (int i = 0; i < nt_dimension; i++) {
                assert selfMergerNode != null;
                if (selfMergerNode.getRtt() == -1) break;
                assert neighbourTable != null;
                if (neighbourTable[i].getRtt() == -1) {
                    neighbourTable[i] = selfMergerNode;
                    break;
                } else if (neighbourTable[i].getRtt() <= selfMergerNode.getRtt()) {
                } else {
                    for (int j = nt_dimension - 1; j >= i + 1; j--) {
                        neighbourTable[j] = neighbourTable[j - 1];
                    }
                    neighbourTable[i] = selfMergerNode;
                }
            }
            for (int i = 0; i < nt_dimension; i++) {
                for (int j = 0; j < nt_dimension; j++) {
                    if (mergerNeighbourTable[i].getRtt() == -1) break;
                    assert neighbourTable != null;
                    if (neighbourTable[j].getRtt() == -1) {
                        neighbourTable[j] = mergerNeighbourTable[i];
                        break;
                    } else if (mergerNeighbourTable[i].getRtt() >= neighbourTable[j].getRtt()) {
                    } else {
                        for (int k = nt_dimension - 1; k >= j + 1; k--) {
                            neighbourTable[k] = neighbourTable[k - 1];
                        }
                        neighbourTable[j] = mergerNeighbourTable[i];
                        break;
                    }
                }
            }
            for (int i = 0; i < nt_dimension; i++) {
                assert neighbourTable != null;
                //System.out.println(neighbourTable[i].getRtt());
            }
            if (neighbourTable == localBaseNeighbourTable) {
                localBaseTablesToXML("BaseRoutingTable", localBaseRoutingTable, localBaseNeighbourTable);
                System.out.println("Base NeighbourTable Merged successfully");
            }
            if (neighbourTable == storageNeighbourTable) {
                localBaseTablesToXML("StorageRoutingTable", storageRoutingTable, storageNeighbourTable);
                System.out.println("Storage NeighbourTable Merged successfully");
            }
        }
    }

    /**
     * @param mergerTableDataFile - get RTT data file from the input buffer of routing table<br>
     * @param layerID - layer id on which the operation needs to be performed
     *
     */
    public File getRTTMergerTable(File mergerTableDataFile, int layerID) {
        B4_Node selfNodeOfMergerTable = getSelfNodeOfMergerTable(mergerTableDataFile.getName());
        B4_Node[] mergerNeighbourTable = getMergerNeighbourTable(mergerTableDataFile.getName());

        String selfNodeIdMerger = selfNodeOfMergerTable.getB4node().getNodeID();
        String selfNodePubMerger = nodeCryptography.pubToStr(selfNodeOfMergerTable.getB4node().getPublicKey());
        String selfHashIdMerger = selfNodeOfMergerTable.getB4node().getHashID();
        String selfIPAddressMerger = selfNodeOfMergerTable.getIpAddress();
        String selfPortAddressMerger = selfNodeOfMergerTable.getPortAddress();
        String selfTransportMerger = selfNodeOfMergerTable.getTransport();
        String selfRTTMerger = "-1";

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            //create root Element
            Element root = doc.createElement("Merger_Neighbour_table");
            doc.appendChild(root);
            root.setAttribute("SELF_NODE_ID", selfNodeIdMerger);
            root.setAttribute("SELF_PUBLIC_KEY", selfNodePubMerger);
            root.setAttribute("SELF_HASH_ID", selfHashIdMerger);
            root.setAttribute("SELF_IP_ADDRESS", selfIPAddressMerger);
            root.setAttribute("SELF_PORT_ADDRESS", selfPortAddressMerger);
            root.setAttribute("SELF_TRANSPORT", selfTransportMerger);
            root.setAttribute("SELF_RTT", selfRTTMerger);


            for (int i = 0; i < nt_dimension; i++) {
                Element row1 = doc.createElement("NEIGHBOUR");
                root.appendChild(row1);
                row1.setAttribute("INDEX", "[" + i + "]");

                Element nodeID = doc.createElement("NODEID");
                nodeID.appendChild(doc.createTextNode(mergerNeighbourTable[i].getB4node().getNodeID()));
                row1.appendChild(nodeID);

                Element nodePub = doc.createElement("PUBLICKEY");
                nodePub.appendChild(doc.createTextNode(nodeCryptography.pubToStr(mergerNeighbourTable[i].getB4node().getPublicKey())));
                row1.appendChild(nodePub);

                Element hashID = doc.createElement("HASHID");
                hashID.appendChild(doc.createTextNode(mergerNeighbourTable[i].getB4node().getHashID()));
                row1.appendChild(hashID);

                Element nodeIP = doc.createElement("NODEIP");
                nodeIP.appendChild(doc.createTextNode(mergerNeighbourTable[i].getIpAddress()));
                row1.appendChild(nodeIP);

                Element nodePort = doc.createElement("NODEPORT");
                nodePort.appendChild(doc.createTextNode(mergerNeighbourTable[i].getPortAddress()));
                row1.appendChild(nodePort);

                Element nodeTransport = doc.createElement("NODETRANSPORT");
                nodeTransport.appendChild(doc.createTextNode(mergerNeighbourTable[i].getTransport()));
                row1.appendChild(nodeTransport);

                Element nodeRTT = doc.createElement("NODERTT");
                nodeRTT.appendChild(doc.createTextNode("-1"));
                row1.appendChild(nodeRTT);

            }
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource domSource = new DOMSource(doc);

            StreamResult streamResult = new StreamResult(new File("GetRTT_" + layerID + "_" + selfNodeIdMerger + ".xml"));
            transformer.transform(domSource, streamResult);
            System.out.println("getRTT File is created");

        } catch (ParserConfigurationException | TransformerException e) {
            e.printStackTrace();
        }
        File file1 = new File("GetRTT_" + layerID + "_" + selfNodeIdMerger + ".xml");
        addFileToOutputBuffer(file1);
        return file1;
    }


    /**
     * @param hashID - hash id received as a query to find the next hop
     * @param layerID - layer id on which the operation is to be performed
     * @return null if next hop is selfNode else return B4_Node object
     * 1. This method is used to find the nextHop for a hashID/NodeID which is received as a query.
     * 2. Initially check whether the hashId/nodeId is equal to localNodeID.
     * 3. Thereafter check whether the localNode is the root node for the given hashId/NodeId.
     */
    public B4_Node findNextHop(String hashID, int layerID) {
        B4_Node[][] routingTable = null;
        if (layerID == 0) {
            routingTable = localBaseRoutingTable;
        } else if (layerID == 1) {
            routingTable = storageRoutingTable;
        }
        String localNodeID = localNode.getB4node().getNodeID();
        //System.out.println("HashID  : " + hashID + " " + "  LocalID  : " + localNodeID);
        char[] hashIdC = hashID.toCharArray();
        char[] localNodeIdC = localNodeID.toCharArray();
        if (hashID.equals(localNodeID)) {
            System.out.println("Current Node is the Root Node");

            /**
             * Nibble wise comparison is made and the first nibble mismatch between hashId and LocalNodeId is identified.
             * <br>This will give value of k (i.e column at which we start looking for next hop).
             * <br>IF this column is not empty check predecessor successor and middle row one by one based on the logic defined to get the next hop.
             */
        } else {
            for (int k = 0; k < rt_dimension; k++) {
                if (hashIdC[k] != localNodeIdC[k]) {
                    //System.out.println("k :" + k);
                    String hashIdChar = Character.toString(hashID.charAt(k));
                    String localNodeIdChar = Character.toString(localNodeID.charAt(k));
                    int hashIdHex = Integer.parseInt(hashIdChar, 16);
                    int localNodeIdInHex = Integer.parseInt(localNodeIdChar, 16);
                    //System.out.println("hashIdHex " + hashIdHex + " localNodeIdInHex " + localNodeIdInHex);
                    if (!localBaseRoutingTable[k][0].getB4node().getNodeID().isEmpty()) {
                        assert routingTable != null;
                        String preNodeIdChar = Character.toString(routingTable[k][0].getB4node().getNodeID().charAt(k));
                        String sucNodeIdChar = Character.toString(routingTable[k][1].getB4node().getNodeID().charAt(k));
                        int preNodeIdHex = Integer.parseInt(preNodeIdChar, 16);
                        int sucNodeIdHex = Integer.parseInt(sucNodeIdChar, 16);

                        /**
                         * Check whether hashId/NodeId lies between predecessor and localNodeId.
                         * Since nibbles are arranged in the form of a ring ranging from 0-15, all possible conditions needs to be checked.
                         * Like predecessor < localNodeId or predecessor > localNodeId and hashId< localNodeID or hashId > localNodeId in a circle
                         * IF true move to next column and check next nibble lies between next nibble of predecessor and localNodeId.
                         * This is Iterated till last column and still hashId nibble lies between predecessor and localNodeId then localNodeId is the root Node.
                         * Hence return Null.
                         */
                        if (preNodeIdHex <= hashIdHex && hashIdHex < localNodeIdInHex || preNodeIdHex > localNodeIdInHex && preNodeIdHex - 16 <= hashIdHex && hashIdHex < localNodeIdInHex || preNodeIdHex > localNodeIdInHex && preNodeIdHex - 16 <= hashIdHex - 16 && hashIdHex - 16 < localNodeIdInHex - 16) {
                            if (k != rt_dimension - 1) {
                                for (int i = k + 1; i < rt_dimension; i++) {
                                    if (!routingTable[i][0].getB4node().getNodeID().isEmpty()) {
                                        String nxtPreNodeIdChar = Character.toString(routingTable[i][0].getB4node().getNodeID().charAt(i));
                                        String nxtHashIdChar = Character.toString(hashID.charAt(i));
                                        String nxtLocalNodeIdChar = Character.toString(localNodeID.charAt(i));
                                        String nxtSucNodeIdChar = Character.toString(routingTable[i][1].getB4node().getNodeID().charAt(i));

                                        int nxtPreNodeIdHex = Integer.parseInt(nxtPreNodeIdChar, 16);
                                        int nxtHashIdHex = Integer.parseInt(nxtHashIdChar, 16);
                                        int nxtLocalNodeIdInHex = Integer.parseInt(nxtLocalNodeIdChar, 16);
                                        int nxtSucNodeIdHex = Integer.parseInt(nxtSucNodeIdChar, 16);

                                        if (nxtPreNodeIdHex <= nxtHashIdHex && nxtHashIdHex < nxtLocalNodeIdInHex || nxtPreNodeIdHex > nxtLocalNodeIdInHex && nxtPreNodeIdHex - 16 <= nxtHashIdHex && nxtHashIdHex < nxtLocalNodeIdInHex || nxtPreNodeIdHex > nxtLocalNodeIdInHex && nxtPreNodeIdHex - 16 <= nxtHashIdHex - 16 && nxtHashIdHex - 16 < nxtLocalNodeIdInHex - 16) {
                                            if (i != rt_dimension - 1) continue;
                                            else return null;
                                        } else if (nxtSucNodeIdHex >= nxtHashIdHex && nxtHashIdHex > nxtLocalNodeIdInHex || nxtSucNodeIdHex < nxtLocalNodeIdInHex && nxtSucNodeIdHex + 16 >= nxtHashIdHex && nxtHashIdHex > nxtLocalNodeIdInHex || nxtSucNodeIdHex < nxtLocalNodeIdInHex && nxtSucNodeIdHex + 16 >= nxtHashIdHex + 16 && nxtHashIdHex + 16 > nxtLocalNodeIdInHex) {
                                            return routingTable[i][1];
                                        } else if (!routingTable[i][2].getB4node().getNodeID().isEmpty()) {
                                            String nxtMidNodeIdChar = Character.toString(routingTable[i][2].getB4node().getNodeID().charAt(i));
                                            int nxtMidNodeIdHex = Integer.parseInt(nxtMidNodeIdChar, 16);
                                            if (nxtSucNodeIdHex < nxtHashIdHex && nxtHashIdHex < nxtMidNodeIdHex || nxtSucNodeIdHex > nxtMidNodeIdHex && nxtSucNodeIdHex - 16 < nxtHashIdHex && nxtHashIdHex < nxtMidNodeIdHex || nxtSucNodeIdHex > nxtMidNodeIdHex && nxtSucNodeIdHex - 16 < nxtHashIdHex - 16 && nxtHashIdHex - 16 < nxtMidNodeIdHex) {
                                                return routingTable[i][1];
                                            } else if (nxtPreNodeIdHex > nxtHashIdHex && nxtHashIdHex > nxtMidNodeIdHex || nxtPreNodeIdHex < nxtMidNodeIdHex && nxtPreNodeIdHex + 16 > nxtHashIdHex && nxtHashIdHex > nxtMidNodeIdHex || nxtPreNodeIdHex < nxtMidNodeIdHex && nxtPreNodeIdHex + 16 > nxtHashIdHex + 16 && nxtHashIdHex + 16 > nxtMidNodeIdHex) {
                                                return routingTable[i][2];
                                            }
                                        } else if (routingTable[i][2].getB4node().getNodeID().isEmpty()) {
                                            return routingTable[i][1];
                                        }
                                        if (i == rt_dimension - 1) return null;
                                    }
                                }
                            } else return null;

                            /**
                             * Else Check whether hashId/NodeId lies between localNodeId and successor.
                             * Since nibbles are arranged in the form of a ring ranging from 0-15, all possible conditions needs to be checked.
                             * IF true return successorNodeId.
                             */
                        } else if (sucNodeIdHex >= hashIdHex && hashIdHex > localNodeIdInHex || sucNodeIdHex < localNodeIdInHex && sucNodeIdHex + 16 >= hashIdHex && hashIdHex > localNodeIdInHex || sucNodeIdHex < localNodeIdInHex && sucNodeIdHex + 16 >= hashIdHex + 16 && hashIdHex + 16 > localNodeIdInHex) {
                            return routingTable[k][1];

                        } else if (!routingTable[k][2].getB4node().getNodeID().isEmpty()) {
                            String midNodeIdChar = Character.toString(routingTable[k][2].getB4node().getNodeID().charAt(k));
                            int midNodeIdHex = Integer.parseInt(midNodeIdChar, 16);

                            /**
                             * Else Check whether hashId/NodeId lies between successor and middle.
                             * Since nibbles are arranged in the form of a ring ranging from 0-15, all possible conditions needs to be checked.
                             * IF true return successorNodeId.
                             */
                            if (sucNodeIdHex < hashIdHex && hashIdHex < midNodeIdHex || sucNodeIdHex > midNodeIdHex && sucNodeIdHex - 16 < hashIdHex && hashIdHex < midNodeIdHex || sucNodeIdHex > midNodeIdHex && sucNodeIdHex - 16 < hashIdHex - 16 && hashIdHex - 16 < midNodeIdHex) {
                                return routingTable[k][1];

                                /**
                                 * Else Check whether hashId/NodeId lies between middle and predecessor.
                                 * Since nibbles are arranged in the form of a ring ranging from 0-15, all possible conditions needs to be checked.
                                 * IF true return middleNodeId.
                                 */
                            } else if (preNodeIdHex > hashIdHex && hashIdHex > midNodeIdHex || preNodeIdHex < midNodeIdHex && preNodeIdHex + 16 > hashIdHex && hashIdHex > midNodeIdHex || preNodeIdHex < midNodeIdHex && preNodeIdHex + 16 > hashIdHex + 16 && hashIdHex + 16 > midNodeIdHex) {
                                return routingTable[k][2];
                            }
                        } else if (routingTable[k][2].getB4node().getNodeID().isEmpty()) {
                            return routingTable[k][1];
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * @param rtFileName         0     * @param routingTableName
     * @param neighbourTableName
     */
    public void purgeRTEntry(String rtFileName, B4_Node[][] routingTableName, B4_Node[] neighbourTableName) {
        //Two counter arrays were created to keep track of no of failed ping.
        int[][] counter_rtable = new int[rt_dimension][3];
        int[] counter_neighbour = new int[nt_dimension];
        long currentTime = System.currentTimeMillis();
        System.out.println("I am in main Thread");

        // New thread is created
        Thread purgeThread = new Thread(() -> {
            System.out.println("Thread is started");
            //count will decide the number of times the while loop will run.
            //I have chosen count value four here.It can be changed to any other value depending on the requirment
            int count = 0;
            int dataPurged_RT = 0;
            int dataPurged_Neighbour = 0;
            long sleepingTime = 0;
            while (true) {
                while (!(count >= 4)) {
                    for (int i = 0; i < rt_dimension; i++) {
                        for (int j = 0; j < 3; j++) {
                            String ipAddressBase = routingTableName[i][j].getIpAddress();
                            System.out.println(ipAddressBase);
                            if (!ipAddressBase.isEmpty()) {
                                try {
                                    InetAddress ping = InetAddress.getByName(ipAddressBase);
                                    if (ping.isReachable(1000)) {
                                        System.out.println("Host is Reachable");
                                        counter_rtable[i][j] = 0;
                                    } else {
                                        System.out.println("Not reachable");
                                        counter_rtable[i][j] = counter_rtable[i][j] + 1;
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                            if (counter_rtable[i][j] == 4) {
                                routingTableName[i][j] = new B4_Node(null, "", "", "");
                                System.out.println("Data is purged");
                                dataPurged_RT = dataPurged_RT + 1;
                                counter_rtable[i][j] = 0;
                            }
                        }
                    }
                    for (int k = 0; k < 16; k++) {
                        String ipAddressNeighbour = neighbourTableName[k].getIpAddress();
                        if (!ipAddressNeighbour.isEmpty()) {
                            try {
                                InetAddress ping = InetAddress.getByName(ipAddressNeighbour);
                                if (ping.isReachable(1000)) {
                                    System.out.println("Host is Reachable");
                                    counter_neighbour[k] = 0;
                                } else {
                                    System.out.println("Not reachable");
                                    counter_neighbour[k] = counter_neighbour[k] + 1;
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        if (counter_neighbour[k] == 4) {
                            neighbourTableName[k] = new B4_Node(null, "", "", "", -1);
                            System.out.println("Data is purged");
                            dataPurged_Neighbour = dataPurged_Neighbour + 1;
                            counter_neighbour[k] = 0;
                        }
                    }
                    localBaseTablesToXML(rtFileName, routingTableName, neighbourTableName);
                    count = count + 1;
                }
                count = 0;
                System.out.println(dataPurged_Neighbour);
                System.out.println(dataPurged_RT);
                if (dataPurged_RT == 0 && dataPurged_Neighbour == 0) {
                    try {
                        sleepingTime = sleepingTime + incrementTime;
                        System.out.println("Going to sleeping for " + sleepingTime);
                        Thread.sleep(sleepingTime);
                        dataPurged_Neighbour = 0;
                        dataPurged_RT = 0;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    try {
                        sleepingTime = sleepTime;
                        System.out.println("going for sleeping for " + sleepingTime);
                        Thread.sleep(sleepingTime);
                        dataPurged_Neighbour = 0;
                        dataPurged_RT = 0;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        purgeThread.start();
    }

    public B4_Node[][] getLocalBaseRoutingTable() {
        return localBaseRoutingTable;
    }

    public B4_Node[] getLocalBaseNeighbourTable() {
        return localBaseNeighbourTable;
    }

    public B4_Node[][] getStorageRoutingTable() {
        return storageRoutingTable;
    }

    public B4_Node[] getStorageNeighbourTable() {
        return storageNeighbourTable;
    }

    public RoutingManagerBuffer getRoutingManagerBuffer() {
        return routingManagerBuffer;
    }

    public boolean addFileToInputBuffer(File file) {
        boolean isAdded = false;
        isAdded = routingManagerBuffer.addToInputBuffer(file);
        return isAdded;
    }

    public boolean addFileToOutputBuffer(File file) {
        boolean isAdded = false;
        isAdded = routingManagerBuffer.addToOutputBuffer(file);
        return isAdded;
    }

    public void fetchFileFromInputBuffer() {
        Thread fetchThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    File file = routingManagerBuffer.fetchFromInputBuffer();
                    if (!(file == null)) {
                        System.out.println(file.getName());
                        System.out.println("New file fetched");
                        if (file.getName().startsWith("0")) {
                            mergeRoutingTable(file, 0);
                            getRTTMergerTable(file,0);
                            System.out.println("Confirm it is 0");
                        } else if (file.getName().startsWith("1")) {
                            mergeRoutingTable(file, 1);
                            getRTTMergerTable(file, 1);
                            System.out.println("Confirm it is 1");
                        } else if (file.getName().startsWith("RcvRTT_0")){
                            mergeNeighbourTable(file,0);
                        } else if (file.getName().startsWith("RcvRTT_1")){
                            mergeNeighbourTable(file,1);
                        }
                        System.out.println("Routing Table updated !!!");
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            }
        });

        fetchThread.start();
    }

    public File fetchFileFromOutputBuffer() {
        File file = routingManagerBuffer.fetchFromOutputBuffer();
        return file;
    }

    /**
     * This method is used for setting Local Node information.
     * <br>Presently it is hardcoded (will be amended later).
     */
    private void setLocalNode() {
        localNode = new B4_Node(new B4_NodeTuple(b4_nodeGeneration.getNodeID(), b4_nodeGeneration.getPublicKey(), b4_nodeGeneration.getHashID()), selfIPAddress, selfPortAddress, selfTransportAddress);
    }

    /**
     * @param mergerFile
     * @return B4_RoutingTable Object
     */
    private B4_Node[][] getMergerRoutingTable(String mergerFile) {
        B4_RoutingTables mergerTable = new B4_RoutingTables(mergerFile);
        return mergerTable.getRoutingTable();
    }

    /**
     * @param mergerFile
     * @return B4_RoutingTable Object
     */
    private B4_Node[] getMergerNeighbourTable(String mergerFile) {
        B4_RoutingTables mergerTable = new B4_RoutingTables(mergerFile);
        return mergerTable.getNeighbourTable();
    }

    /**
     * @param mergerFile-Merger File need to be added
     * @return B4_Node Object
     */
    private B4_Node getSelfNodeOfMergerTable(String mergerFile) {
        B4_RoutingTables mergerTable = new B4_RoutingTables(mergerFile);
        return mergerTable.getSelfNode();
    }

    /**
     * @param mergerNode
     * @param routingTable All the algorithm for merging the routing and neighbour table is defined in this function.
     */
    private void mergerRT(B4_Node mergerNode, B4_Node[][] routingTable) {
        int preNodeIdInHex;
        int sucNodeIdInHex;
        String mergerNodeID = mergerNode.getB4node().getNodeID();
        String localNodeID = localNode.getB4node().getNodeID();
        //System.out.println("mergerNodeID  " + mergerNodeID + "\nLocalNodeID  " + localNodeID);
        char[] mergerNodeidInCharArray = mergerNodeID.toCharArray();
        char[] localNodeidInCharArray = localNodeID.toCharArray();

        /**
         * 1. Check for the first mismatch in nibble between mergerNodeId and localNodeId.
         * 2. IF predecessor,successor and middle entry is empty, mergerNode is added to predecessor and successor.
         * 3. Else, conditions were checked one by one.
         * 4. First condition is if mergerNodeId lies between the existing predecessor and localNodeId
         * 5. Second condition is mergerNodeId lies between the localNodeId and existing SuccessorNodeId
         * 6. Third condition is if mergerNodeId lies between successor and predecessor.
         */
        for (int k = 0; k < rt_dimension; k++) {
            //System.out.println(k);
            char[] preNodeIdCharArray = routingTable[k][0].getB4node().getNodeID().toCharArray();
            char[] sucNodeIdCharArray = routingTable[k][1].getB4node().getNodeID().toCharArray();
            if (mergerNodeidInCharArray[k] != localNodeidInCharArray[k]) {
                String mergerNodeIdChar = Character.toString(mergerNodeID.charAt(k));
                String localNodeIdChar = Character.toString(localNodeID.charAt(k));
                int mergerNodeIdInHex = Integer.parseInt(mergerNodeIdChar, 16);
                int localNodeIdInHex = Integer.parseInt(localNodeIdChar, 16);
                //System.out.println("K " + k);
                //System.out.println("mergerNodeIDHex  " + mergerNodeIdInHex + "  LocalNodeIDHex  " + localNodeIdInHex);

                if (routingTable[k][0].getB4node().getNodeID().isEmpty() && routingTable[k][1].getB4node().getNodeID().isEmpty() && routingTable[k][2].getB4node().getNodeID().isEmpty()) {
                    //System.out.println(k + " th column is empty");
                    routingTable[k][0] = mergerNode;
                    routingTable[k][1] = mergerNode;
                    break;
                } else {
                    String preNodeIdChar = Character.toString(routingTable[k][0].getB4node().getNodeID().charAt(k));
                    preNodeIdInHex = Integer.parseInt(preNodeIdChar, 16);
                    String sucNodeIdChar = Character.toString(routingTable[k][1].getB4node().getNodeID().charAt(k));
                    sucNodeIdInHex = Integer.parseInt(sucNodeIdChar, 16);
                    //System.out.println("preNodeIdHex  " + preNodeIdInHex + "  sucNodeIdHex  " + sucNodeIdInHex);

                    /**
                     * 1. Following is for checking the first Condition ie mergerNodeId lies between predecessor and localNodeId.
                     * 2. Since we are looking into a circular ring with nibble value range from 0-15,all possible conditions need to be checked.
                     * 3. Like Predecessor > LocalNodeId or Predecessor < LocalNodeId  and similarly for all cases of mergerNodeId.
                     * 4. Like mergerNodeId > LocalNodeId or mergerNodeId < LocalNodeId.
                     */
                    if (preNodeIdInHex <= mergerNodeIdInHex && mergerNodeIdInHex < localNodeIdInHex) {
                        //System.out.println("In Predecessor:First part");
                        if (preNodeIdInHex == mergerNodeIdInHex) {
                            for (int i = k + 1; i < rt_dimension; i++) {
                                if (mergerNodeidInCharArray[i] != preNodeIdCharArray[i]) {
                                    String nxtPreIdInChar = Character.toString(routingTable[k][0].getB4node().getNodeID().charAt(i));
                                    String nxtMergerIdChar = Character.toString(mergerNodeID.charAt(i));
                                    int nxtPreNodeIdInHex = Integer.parseInt(nxtPreIdInChar, 16);
                                    int nxtMergerNodeIdInHex = Integer.parseInt(nxtMergerIdChar, 16);
                                    if (nxtMergerNodeIdInHex > nxtPreNodeIdInHex) {
                                        routingTable[k][0] = mergerNode;
                                    }
                                    break;
                                }
                            }
                        } else if (preNodeIdInHex == sucNodeIdInHex) {
                            routingTable[k][0] = mergerNode;
                            break;
                        } else {
                            routingTable[k][2] = routingTable[k][0];
                            routingTable[k][0] = mergerNode;
                            break;
                        }
                    } else if (preNodeIdInHex > localNodeIdInHex) {
                        //System.out.println("In Predecessor : 2nd Part");
                        if (preNodeIdInHex - 16 < mergerNodeIdInHex && mergerNodeIdInHex < localNodeIdInHex) {
                            if (preNodeIdInHex != sucNodeIdInHex) {
                                routingTable[k][2] = routingTable[k][0];
                            }
                            routingTable[k][0] = mergerNode;
                            break;

                        } else if (mergerNodeIdInHex > localNodeIdInHex && preNodeIdInHex - 16 < mergerNodeIdInHex - 16 && mergerNodeIdInHex - 16 < localNodeIdInHex) {
                            if (preNodeIdInHex != sucNodeIdInHex) {
                                routingTable[k][2] = routingTable[k][0];
                            }
                            routingTable[k][0] = mergerNode;
                            break;

                        } else if (preNodeIdInHex == mergerNodeIdInHex) {
                            for (int i = k + 1; i < rt_dimension; i++) {
                                if (mergerNodeidInCharArray[i] != preNodeIdCharArray[i]) {
                                    String nxtPreIdInChar = Character.toString(routingTable[k][0].getB4node().getNodeID().charAt(i));
                                    String nxtMergerIdChar = Character.toString(mergerNodeID.charAt(i));
                                    int nxtPreNodeIdInHex = Integer.parseInt(nxtPreIdInChar, 16);
                                    int nxtMergerNodeIdInHex = Integer.parseInt(nxtMergerIdChar, 16);
                                    if (nxtMergerNodeIdInHex > nxtPreNodeIdInHex) {
                                        routingTable[k][0] = mergerNode;
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    /**
                     * 1. Following is for checking the second condition ie mergerNodeId lies between successor and localNodeId.
                     * 2. Since we are looking into a circular ring with nibble value range from 0-15,all possible conditions need to be checked.
                     * 3. Like Successor > LocalNodeId or Successor < LocalNodeId  and similarly for all cases of mergerNodeId.
                     * 4. Like mergerNodeId > LocalNodeId or mergerNodeId < LocalNodeId.
                     */
                    if (sucNodeIdInHex >= mergerNodeIdInHex && mergerNodeIdInHex > localNodeIdInHex) {
                        //System.out.println("In successor : Ist Part");
                        if (sucNodeIdInHex == mergerNodeIdInHex) {
                            for (int i = k + 1; i < rt_dimension; i++) {
                                if (mergerNodeidInCharArray[i] != sucNodeIdCharArray[i]) {
                                    String nxtSucIdInChar = Character.toString(routingTable[k][1].getB4node().getNodeID().charAt(i));
                                    String nxtMergerIdChar = Character.toString(mergerNodeID.charAt(i));
                                    int nxtSucNodeIdInHex = Integer.parseInt(nxtSucIdInChar, 16);
                                    int nxtMergerNodeIdInHex = Integer.parseInt(nxtMergerIdChar, 16);
                                    if (nxtMergerNodeIdInHex < nxtSucNodeIdInHex) {
                                        routingTable[k][1] = mergerNode;
                                    }
                                    break;
                                }
                            }
                        } else if (preNodeIdInHex == sucNodeIdInHex) {
                            routingTable[k][1] = mergerNode;
                            break;
                        } else {
                            routingTable[k][2] = routingTable[k][1];
                            routingTable[k][1] = mergerNode;
                            break;
                        }
                    } else if (sucNodeIdInHex < localNodeIdInHex) {
                        //System.out.println("In successor : 2nd Part");
                        if (sucNodeIdInHex + 16 > mergerNodeIdInHex && mergerNodeIdInHex > localNodeIdInHex) {
                            if (preNodeIdInHex != sucNodeIdInHex) {
                                routingTable[k][2] = routingTable[k][1];
                            }
                            routingTable[k][1] = mergerNode;
                            break;

                        } else if (mergerNodeIdInHex < localNodeIdInHex && sucNodeIdInHex + 16 > mergerNodeIdInHex + 16 && mergerNodeIdInHex + 16 > localNodeIdInHex) {
                            if (preNodeIdInHex != sucNodeIdInHex) {
                                routingTable[k][2] = routingTable[k][1];
                            }
                            routingTable[k][1] = mergerNode;
                            break;
                        } else if (sucNodeIdInHex == mergerNodeIdInHex) {
                            for (int i = k + 1; i < rt_dimension; i++) {
                                if (mergerNodeidInCharArray[i] != sucNodeIdCharArray[i]) {
                                    String nxtSucIdInChar = Character.toString(routingTable[k][1].getB4node().getNodeID().charAt(i));
                                    String nxtMergerIdChar = Character.toString(mergerNodeID.charAt(i));
                                    int nxtSucNodeIdInHex = Integer.parseInt(nxtSucIdInChar, 16);
                                    int nxtMergerNodeIdInHex = Integer.parseInt(nxtMergerIdChar, 16);
                                    if (nxtMergerNodeIdInHex < nxtSucNodeIdInHex) {
                                        routingTable[k][1] = mergerNode;
                                    }
                                    break;
                                }
                            }
                        }
                    }

                    /**
                     * 1. Following is for checking the Third condition ie mergerNodeId lies between predecessor and successor.
                     * 2. Since we are looking into a circular ring with nibble value range from 0-15,all possible conditions need to be checked.z
                     */
                    if (sucNodeIdInHex < mergerNodeIdInHex && mergerNodeIdInHex < preNodeIdInHex || sucNodeIdInHex < mergerNodeIdInHex && mergerNodeIdInHex < preNodeIdInHex + 16 || sucNodeIdInHex < mergerNodeIdInHex + 16 && mergerNodeIdInHex + 16 < preNodeIdInHex + 16) {
                        //System.out.println("In Middle");
                        if (!routingTable[k][2].getB4node().getNodeID().isEmpty()) {
                            String existingMidNodeIdChar = Character.toString(routingTable[k][2].getB4node().getNodeID().charAt(k));
                            int existingMidNodeIdHex = Integer.parseInt(existingMidNodeIdChar, 16);
                            if (Math.abs(((localNodeIdInHex + 8) % 16) - mergerNodeIdInHex) < Math.abs(((localNodeIdInHex + 8) % 16) - existingMidNodeIdHex)) {
                                routingTable[k][2] = mergerNode;
                                break;
                            }
                        } else {
                            routingTable[k][2] = mergerNode;
                            break;
                        }
                    }
                }
                break;
            }
        }
    }

    /**
     * @param fileHeading
     * @param routingTable
     * @param neighbourTable <br>This function is used to convert the Routing Table in the form of an array to xml format
     *                       <br>Here XML parsing is used.
     */
    private void localBaseTablesToXML(String fileHeading, B4_Node[][] routingTable, B4_Node[] neighbourTable) {
        String selfNodeId = localNode.getB4node().getNodeID();
        String selfNodePub = nodeCryptography.pubToStr(localNode.getB4node().getPublicKey());
        String selfHashId = localNode.getB4node().getHashID();
        String selfIPAddress = localNode.getIpAddress();
        String selfPortAddress = localNode.getPortAddress();
        String selfTransport = localNode.getTransport();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            //create root Element
            Element root = doc.createElement(fileHeading);
            doc.appendChild(root);
            root.setAttribute("SELF_NODE_ID", selfNodeId);
            root.setAttribute("SELF_PUBLIC_KEY", selfNodePub);
            root.setAttribute("SELF_HASH_ID", selfHashId);
            root.setAttribute("SELF_IP_ADDRESS", selfIPAddress);
            root.setAttribute("SELF_PORT_ADDRESS", selfPortAddress);
            root.setAttribute("SELF_TRANSPORT", selfTransport);

            for (int i = 0; i < rt_dimension; i++) {
                for (int j = 0; j < 3; j++) {
                    Element row = doc.createElement("B4_Node");
                    root.appendChild(row);
                    row.setAttribute("INDEX", "[" + i + "]" + "[" + j + "]");

                    Element nodeID = doc.createElement("NODEID");
                    nodeID.appendChild(doc.createTextNode(routingTable[i][j].getB4node().getNodeID()));
                    row.appendChild(nodeID);

                    Element nodePub = doc.createElement("PUBLICKEY");
                    nodePub.appendChild(doc.createTextNode(nodeCryptography.pubToStr(routingTable[i][j].getB4node().getPublicKey())));
                    row.appendChild(nodePub);

                    Element hashID = doc.createElement("HASHID");
                    hashID.appendChild(doc.createTextNode(routingTable[i][j].getB4node().getNodeID()));
                    row.appendChild(hashID);

                    Element nodeIP = doc.createElement("NODEIP");
                    nodeIP.appendChild(doc.createTextNode(routingTable[i][j].getIpAddress()));
                    row.appendChild(nodeIP);

                    Element nodePort = doc.createElement("NODEPORT");
                    nodePort.appendChild(doc.createTextNode(routingTable[i][j].getPortAddress()));
                    row.appendChild(nodePort);

                    Element nodeTransport = doc.createElement("NODETRANSPORT");
                    nodeTransport.appendChild(doc.createTextNode(routingTable[i][j].getTransport()));
                    row.appendChild(nodeTransport);
                }
            }
            for (int i = 0; i < nt_dimension; i++) {
                Element row1 = doc.createElement("NEIGHBOUR");
                root.appendChild(row1);
                row1.setAttribute("INDEX", "[" + i + "]");

                Element nodeID = doc.createElement("NODEID");
                nodeID.appendChild(doc.createTextNode(neighbourTable[i].getB4node().getNodeID()));
                row1.appendChild(nodeID);

                Element nodePub = doc.createElement("PUBLICKEY");
                nodePub.appendChild(doc.createTextNode(nodeCryptography.pubToStr(neighbourTable[i].getB4node().getPublicKey())));
                row1.appendChild(nodePub);

                Element hashID = doc.createElement("HASHID");
                hashID.appendChild(doc.createTextNode(neighbourTable[i].getB4node().getHashID()));
                row1.appendChild(hashID);

                Element nodeIP = doc.createElement("NODEIP");
                nodeIP.appendChild(doc.createTextNode(neighbourTable[i].getIpAddress()));
                row1.appendChild(nodeIP);

                Element nodePort = doc.createElement("NODEPORT");
                nodePort.appendChild(doc.createTextNode(neighbourTable[i].getPortAddress()));
                row1.appendChild(nodePort);

                Element nodeTransport = doc.createElement("NODETRANSPORT");
                nodeTransport.appendChild(doc.createTextNode(neighbourTable[i].getTransport()));
                row1.appendChild(nodeTransport);

                Element nodeRTT = doc.createElement("NODERTT");
                nodeRTT.appendChild(doc.createTextNode(String.valueOf(neighbourTable[i].getRtt())));
                row1.appendChild(nodeRTT);
            }
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource domSource = new DOMSource(doc);
            /**
             * For debugging//
             * StreamResult streamResult = new StreamResult(System.out);
             **/
            StreamResult streamResult = new StreamResult(new File(fileHeading + ".xml"));
            transformer.transform(domSource, streamResult);
            System.out.println(fileHeading + " file updated");
        } catch (ParserConfigurationException | TransformerException e) {
            e.printStackTrace();
        }
    }

}
