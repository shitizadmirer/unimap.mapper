package ro.ulbsibiu.acaps.mapper.es;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import org.apache.log4j.Logger;

import ro.ulbsibiu.acaps.ctg.xml.apcg.ApcgType;
import ro.ulbsibiu.acaps.ctg.xml.apcg.CoreType;
import ro.ulbsibiu.acaps.ctg.xml.apcg.TaskType;
import ro.ulbsibiu.acaps.ctg.xml.ctg.CommunicationType;
import ro.ulbsibiu.acaps.ctg.xml.ctg.CtgType;
import ro.ulbsibiu.acaps.ctg.xml.mapping.MapType;
import ro.ulbsibiu.acaps.ctg.xml.mapping.MappingType;
import ro.ulbsibiu.acaps.mapper.Mapper;
import ro.ulbsibiu.acaps.mapper.MapperDatabase;
import ro.ulbsibiu.acaps.mapper.TooFewNocNodesException;
import ro.ulbsibiu.acaps.mapper.sa.Core;
import ro.ulbsibiu.acaps.mapper.sa.SimulatedAnnealingMapper;
import ro.ulbsibiu.acaps.mapper.util.ApcgFilenameFilter;
import ro.ulbsibiu.acaps.mapper.util.HeapUsageMonitor;
import ro.ulbsibiu.acaps.mapper.util.MapperInputProcessor;
import ro.ulbsibiu.acaps.mapper.util.MathUtils;
import ro.ulbsibiu.acaps.mapper.util.TimeUtils;
import ro.ulbsibiu.acaps.noc.xml.link.LinkType;
import ro.ulbsibiu.acaps.noc.xml.node.NodeType;
import ro.ulbsibiu.acaps.noc.xml.node.ObjectFactory;
import ro.ulbsibiu.acaps.noc.xml.node.RoutingTableEntryType;
import ro.ulbsibiu.acaps.noc.xml.node.TopologyParameterType;

/**
 * This {@link Mapper} is inspired from the {@link SimulatedAnnealingMapper}.
 * The difference is that it searches for the best mapping by generating all the
 * possible mappings.
 * 
 * <p>
 * Note that currently, this algorithm works only with M x N 2D mesh NoCs
 * </p>
 * 
 * @author cipi
 * 
 */
public class ExhaustiveSearchMapper implements Mapper {
	
	/**
	 * Logger for this class
	 */
	private static final Logger logger = Logger
			.getLogger(ExhaustiveSearchMapper.class);
	
	private static final String MAPPER_ID = "es";

	private static final int NORTH = 0;

	private static final int SOUTH = 1;

	private static final int EAST = 2;

	private static final int WEST = 3;

	/**
	 * how costly is each unit of link overload (a link is overloaded when it
	 * has to send more bits/s than its bandwidth)
	 */
	private final float OVERLOAD_UNIT_COST = 1000000000;

	/**
	 * whether or not to build routing table too. When the SA algorithm builds
	 * the routing table, the mapping process takes more time but, this should
	 * yield better performance
	 */
	private boolean buildRoutingTable;

	/**
	 * When the algorithm builds the routing table, it avoids deadlocks by
	 * employing a set of legal turns.
	 * 
	 * @author cipi
	 * 
	 */
	public enum LegalTurnSet {
		WEST_FIRST, ODD_EVEN
	}

	/**
	 * what {@link LegalTurnSet} the SA algorithm should use (this is useful
	 * only when the routing table is built)
	 */
	private LegalTurnSet legalTurnSet;

	/** the link bandwidth */
	private double linkBandwidth;
	
	/** energy consumption per bit read */
	private float bufReadEBit;

	/** energy consumption per bit write */
	private float bufWriteEBit;

	/** the number of nodes (nodes) from the NoC */
	private int nodesNumber;

	/** the number of mesh nodes placed horizontally */
	private int hSize;

	/**
	 * the number of processes (tasks). Note that each core has only one task
	 * associated to it.
	 */
	private int coresNumber;
	
	/** counts how many cores were parsed from the parsed APCGs */
	private int previousCoreCount = 0;

	/**
	 * the number of links from the NoC
	 */
	private int linksNumber;

	/** the nodes from the Network-on-Chip (NoC) */
	private NodeType[] nodes;

	/** the processes (tasks, cores) */
	private Core[] cores;

	/** the communication channels from the NoC */
	private ro.ulbsibiu.acaps.noc.xml.link.LinkType[] links;

	/**
	 * what links are used by nodes to communicate (each source - destination
	 * node pair has a list of link IDs). The matrix must have size
	 * <tt>nodesNumber x nodesNumber</tt>. <b>This must be <tt>null</tt> when
	 * <tt>buildRoutingTable</tt> is <tt>true</tt> </b>
	 */
	private List<Integer>[][] linkUsageList = null;

	/**
	 * per link bandwidth usage (used only when the algorithm doesn't build the
	 * routing table)
	 */
	private int[] linkBandwidthUsage = null;

	/**
	 * per link bandwidth usage (used only when the algorithm builds the routing
	 * table)
	 */
	private int[][][] synLinkBandwithUsage = null;

	/** holds the generated routing table */
	private int[][][][] generatedRoutingTable = null;

	/** the benchmark's name */
	private String benchmarkName;
	
	/** the CTG ID */
	private String ctgId;
	
	/** the ACPG ID */
	private String apcgId;
	
	/** the directory where the NoC topology is described */
	private File topologyDir;
	
	/** the topology name */
	private String topologyName;
	
	/** the topology size */
	private String topologySize;
	
	private double bestCost = Float.MAX_VALUE;
	
	private String[] bestMapping = null;
	
	private static enum TopologyParameter {
		/** on what row of a 2D mesh the node is located */
		ROW,
		/** on what column of a 2D mesh the node is located */
		COLUMN,
	};
	
	private static final String LINK_IN = "in";
	
	private static final String LINK_OUT = "out";
	
	private Integer[] nodeRows;
	
	private Integer[] nodeColumns;
	
	private String getNodeTopologyParameter(NodeType node,
			TopologyParameter parameter) {
		String value = null;
		if (TopologyParameter.ROW.equals(parameter)
				&& nodeRows[Integer.valueOf(node.getId())] != null) {
			value = Integer.toString(nodeRows[Integer.valueOf(node.getId())]);
		} else {
			if (TopologyParameter.COLUMN.equals(parameter)
					&& nodeColumns[Integer.valueOf(node.getId())] != null) {
				value = Integer.toString(nodeColumns[Integer.valueOf(node
						.getId())]);
			} else {
				List<TopologyParameterType> topologyParameters = node
						.getTopologyParameter();
				for (int i = 0; i < topologyParameters.size(); i++) {
					if (parameter.toString().equalsIgnoreCase(
							topologyParameters.get(i).getType())) {
						value = topologyParameters.get(i).getValue();
						break;
					}
				}
				logger.assertLog(value != null,
						"Couldn't find the topology parameter '" + parameter
								+ "' in the node " + node.getId());

				if (TopologyParameter.ROW.equals(parameter)) {
					nodeRows[Integer.valueOf(node.getId())] = Integer
							.valueOf(value);
				}
				if (TopologyParameter.COLUMN.equals(parameter)) {
					nodeColumns[Integer.valueOf(node.getId())] = Integer
							.valueOf(value);
				}
			}
		}

		return value;
	}
	
	/** routingTables[nodeId][sourceNode][destinationNode] = link ID */
	private int[][][] routingTables;
	
	public void generateXYRoutingTable() {
		for (int n = 0; n < nodes.length; n++) {
			NodeType node = nodes[n];
			for (int i = 0; i < nodesNumber; i++) {
				for (int j = 0; j < nodesNumber; j++) {
					routingTables[Integer.valueOf(node.getId())][i][j] = -2;
				}
			}
	
			for (int dstNode = 0; dstNode < nodesNumber; dstNode++) {
				if (dstNode == Integer.valueOf(node.getId())) { // deliver to me
					routingTables[Integer.valueOf(node.getId())][0][dstNode] = -1;
				} else {
					// check out the dst Node's position first
					int dstRow = dstNode / hSize;
					int dstCol = dstNode % hSize;
		
					int row = Integer.valueOf(getNodeTopologyParameter(node, TopologyParameter.ROW));
					int column = Integer.valueOf(getNodeTopologyParameter(node, TopologyParameter.COLUMN));
					int nextStepRow = row;
					int nextStepCol = column;
		
					if (dstCol != column) { // We should go horizontally
						if (column > dstCol) {
							nextStepCol--;
						} else {
							nextStepCol++;
						}
					} else { // We should go vertically
						if (row > dstRow) {
							nextStepRow--;
						} else {
							nextStepRow++;
						}
					}
		
					for (int i = 0; i < node.getLink().size(); i++) {
						if (LINK_OUT.equals(node.getLink().get(i).getType())) {
							String nodeRow = "-1";
							String nodeColumn = "-1";
							// the links are bidirectional
							if (links[Integer.valueOf(node.getLink().get(i).getValue())].getFirstNode().equals(node.getId())) {
								nodeRow = getNodeTopologyParameter(
										nodes[Integer.valueOf(links[Integer.valueOf(node.getLink().get(i).getValue())].getSecondNode())],
										TopologyParameter.ROW);
								nodeColumn = getNodeTopologyParameter(
										nodes[Integer.valueOf(links[Integer.valueOf(node.getLink().get(i).getValue())].getSecondNode())],
										TopologyParameter.COLUMN);
							} else {
								if (links[Integer.valueOf(node.getLink().get(i).getValue())].getSecondNode().equals(node.getId())) {
									nodeRow = getNodeTopologyParameter(
											nodes[Integer.valueOf(links[Integer.valueOf(node.getLink().get(i).getValue())].getFirstNode())],
											TopologyParameter.ROW);
									nodeColumn = getNodeTopologyParameter(
											nodes[Integer.valueOf(links[Integer.valueOf(node.getLink().get(i).getValue())].getFirstNode())],
											TopologyParameter.COLUMN);
								}
							}
							if (Integer.valueOf(nodeRow) == nextStepRow
									&& Integer.valueOf(nodeColumn) == nextStepCol) {
								routingTables[Integer.valueOf(node.getId())][0][dstNode] = Integer.valueOf(links[Integer
										.valueOf(node.getLink().get(i).getValue())]
										.getId());
								break;
							}
						}
					}
				}
			}
	
			// Duplicate this routing row to the other routing rows.
			for (int i = 1; i < nodesNumber; i++) {
				for (int j = 0; j < nodesNumber; j++) {
					routingTables[Integer.valueOf(node.getId())][i][j] = routingTables[Integer.valueOf(node.getId())][0][j];
				}
			}
		}
	}

	/**
	 * Default constructor
	 * <p>
	 * No routing table is built.
	 * </p>
	 * 
	 * @param benchmarkName
	 *            the benchmark's name
	 * @param ctgId
	 *            the CTG ID
	 * @param topologyName
	 *            the topology name
	 * @param topologySize
	 *            the topology size
	 * @param topologyDir
	 *            the topology directory is used to initialize the NoC topology
	 *            for XML files. These files are split into two categories:
	 *            nodes and links. The nodes are expected to be located into the
	 *            "nodes" subdirectory, and the links into the "links"
	 *            subdirectory
	 * @param coresNumber
	 *            the number of processes (tasks). Note that each core has only
	 *            one task associated to it
	 * @param linkBandwidth
	 *            the bandwidth of each network link
	 */
	public ExhaustiveSearchMapper(String benchmarkName, String ctgId,
			String apcgId, String topologyName, String topologySize,
			File topologyDir, int coresNumber, double linkBandwidth,
			float switchEBit, float linkEBit) throws JAXBException {
		this(benchmarkName, ctgId, apcgId, topologyName, topologySize,
				topologyDir, coresNumber, linkBandwidth, false,
				LegalTurnSet.WEST_FIRST, 1.056f, 2.831f, switchEBit, linkEBit);
	}

	/**
	 * Constructor
	 * 
	 * @param benchmarkName
	 *            the benchmark's name
	 * @param ctgId
	 *            the CTG ID
	 * @param apcgId
	 *            the APCG ID
	 * @param topologyName
	 *            the topology name
	 * @param topologySize
	 *            the topology size
	 * @param topologyDir
	 *            the topology directory is used to initialize the NoC topology
	 *            for XML files. These files are split into two categories:
	 *            nodes and links. The nodes are expected to be located into the
	 *            "nodes" subdirectory, and the links into the "links"
	 *            subdirectory
	 * @param coresNumber
	 *            the number of processes (tasks). Note that each core has only
	 *            one task associated to it
	 * @param linkBandwidth
	 *            the bandwidth of each network link
	 * @param buildRoutingTable
	 *            whether or not to build routing table too
	 * @param legalTurnSet
	 *            what {@link LegalTurnSet} the SA algorithm should use (this is
	 *            useful only when the routing table is built)
	 * @param bufReadEBit
	 *            energy consumption per bit read
	 * @param bufWriteEBit
	 *            energy consumption per bit write
	 * @param switchEBit
	 *            the energy consumed for switching one bit of data
	 * @param linkEBit
	 *            the energy consumed for sending one data bit
	 * @throws JAXBException
	 */
	public ExhaustiveSearchMapper(String benchmarkName, String ctgId, String apcgId,
			String topologyName, String topologySize, File topologyDir, int coresNumber,
			double linkBandwidth, boolean buildRoutingTable,
			LegalTurnSet legalTurnSet, float bufReadEBit, float bufWriteEBit,
			float switchEBit, float linkEBit) throws JAXBException {
		if (topologyDir == null) {
			logger.error("Please specify the NoC topology directory! Stopping...");
			System.exit(0);
		}
		if (!topologyDir.isDirectory()) {
			logger.error("The specified NoC topology directory does not exist or is not a directory! Stopping...");
			System.exit(0);
		}
		this.benchmarkName = benchmarkName;
		this.ctgId = ctgId;
		this.apcgId = apcgId;
		this.topologyName = topologyName;
		this.topologySize = topologySize;
		this.topologyDir = topologyDir;
		this.coresNumber = coresNumber;
		this.linkBandwidth = linkBandwidth;
		this.buildRoutingTable = buildRoutingTable;
		this.legalTurnSet = legalTurnSet;
		this.bufReadEBit = bufReadEBit;
		this.bufWriteEBit = bufWriteEBit;

		initializeNocTopology(switchEBit, linkEBit);
		initializeCores();
	}
	
	@Override
	public String getMapperId() {
		return MAPPER_ID;
	}

	private void initializeCores() {
		cores = new Core[coresNumber];
		for (int i = 0; i < cores.length; i++) {
			cores[i] = new Core(i, null, -1);
			cores[i].setFromCommunication(new long[coresNumber]);
			cores[i].setToCommunication(new long[coresNumber]);
			cores[i].setFromBandwidthRequirement(new long[coresNumber]);
			cores[i].setToBandwidthRequirement(new long[coresNumber]);
		}
	}

	private List<CommunicationType> getCommunications(CtgType ctg, String sourceTaskId) {
		logger.assertLog(ctg != null, null);
		logger.assertLog(sourceTaskId != null, null);
		
		List<CommunicationType> communications = new ArrayList<CommunicationType>();
		List<CommunicationType> communicationTypeList = ctg.getCommunication();
		for (int i = 0; i < communicationTypeList.size(); i++) {
			CommunicationType communicationType = communicationTypeList.get(i);
			if (sourceTaskId.equals(communicationType.getSource().getId())
					|| sourceTaskId.equals(communicationType.getDestination().getId())) {
				communications.add(communicationType);
			}
		}
		
		return communications;
	}

	private String getCoreUid(ApcgType apcg, String sourceTaskId) {
		logger.assertLog(apcg != null, null);
		logger.assertLog(sourceTaskId != null, null);
		
		String coreUid = null;
		
		List<CoreType> cores = apcg.getCore();
		done: for (int i = 0; i < cores.size(); i++) {
			List<TaskType> tasks = cores.get(i).getTask();
			for (int j = 0; j < tasks.size(); j++) {
				if (sourceTaskId.equals(tasks.get(j).getId())) {
					coreUid = cores.get(i).getUid();
					break done;
				}
			}
		}

		return coreUid;
	}
	
	/**
	 * Reads the information from the (Application Characterization Graph) APCG
	 * and its corresponding (Communication Task Graph) CTG. Additionally, it
	 * informs the algorithm about the application's bandwidth requirement. The
	 * bandwidth requirements of the application are expressed as a multiple of
	 * the communication volume. For example, a value of 2 means that each two
	 * communicating IP cores require a bandwidth twice their communication
	 * volume.
	 * 
	 * @param apcg
	 *            the APCG XML
	 * @param ctg
	 *            the CTG XML
	 * @param applicationBandwithRequirement
	 *            the bandwidth requirement of the application
	 */
	public void parseApcg(ApcgType apcg, CtgType ctg, int applicationBandwithRequirement) {
		logger.assertLog(apcg != null, "The APCG cannot be null");
		logger.assertLog(ctg != null, "The CTG cannot be null");
		
		// we use previousCoreCount to shift the cores from each APCG
		List<CoreType> coreList = apcg.getCore();
		for (int i = 0; i < coreList.size(); i++) {
			CoreType coreType = coreList.get(i);
			List<TaskType> taskList = coreType.getTask();
			for (int j = 0; j < taskList.size(); j++) {
				TaskType taskType = taskList.get(j);
				String taskId = taskType.getId();
				cores[previousCoreCount + Integer.valueOf(coreType.getUid())].setApcgId(apcg.getId());
				List<CommunicationType> communications = getCommunications(ctg, taskId);
				for (int k = 0; k < communications.size(); k++) {
					CommunicationType communicationType = communications.get(k);
					String sourceId = communicationType.getSource().getId();
					String destinationId = communicationType.getDestination().getId();
					
					String sourceCoreId = null;
					String destinationCoreId = null;
					
					if (taskId.equals(sourceId)) {
						sourceCoreId = getCoreUid(apcg, sourceId);
						destinationCoreId = getCoreUid(apcg, destinationId);
						cores[previousCoreCount + Integer.valueOf(sourceCoreId)].setCoreId(Integer.valueOf(coreType.getUid()));
					}
					if (taskId.equals(destinationId)) {
						sourceCoreId = getCoreUid(apcg, sourceId);
						destinationCoreId = getCoreUid(apcg, destinationId);
						cores[previousCoreCount + Integer.valueOf(destinationCoreId)].setCoreId(Integer.valueOf(coreType.getUid()));
					}
					
					logger.assertLog(sourceCoreId != null, null);
					logger.assertLog(destinationCoreId != null, null);
					
					if (sourceCoreId.equals(destinationCoreId)) {
						logger.warn("Ignoring communication between tasks "
								+ sourceId + " and " + destinationId
								+ " because they are on the same core ("
								+ sourceCoreId + ")");
					} else {
						cores[previousCoreCount + Integer.valueOf(sourceCoreId)]
								.getToCommunication()[previousCoreCount
								+ Integer.valueOf(destinationCoreId)] = (long) communicationType
								.getVolume();
						cores[previousCoreCount + Integer.valueOf(sourceCoreId)]
								.getToBandwidthRequirement()[previousCoreCount
								+ Integer.valueOf(destinationCoreId)] = (long) (applicationBandwithRequirement * communicationType
								.getVolume());
						cores[previousCoreCount
								+ Integer.valueOf(destinationCoreId)]
								.getFromCommunication()[previousCoreCount
								+ Integer.valueOf(sourceCoreId)] = (long) communicationType
								.getVolume();
						cores[previousCoreCount
								+ Integer.valueOf(destinationCoreId)]
								.getFromBandwidthRequirement()[previousCoreCount
								+ Integer.valueOf(sourceCoreId)] = (long) (applicationBandwithRequirement * communicationType
								.getVolume());
					}
				}
			}
		}
		previousCoreCount += coreList.size();
	}
	
	/**
	 * Initializes the NoC topology for XML files. These files are split into
	 * two categories: nodes and links. The nodes are expected to be located
	 * into the "nodes" subdirectory, and the links into the "links"
	 * subdirectory.
	 * 
	 * @param switchEBit
	 *            the energy consumed for switching a bit of data
	 * @param linkEBit
	 *            the energy consumed for sending a data bit
	 * @throws JAXBException 
	 */
	private void initializeNocTopology(float switchEBit, float linkEBit) throws JAXBException {
		// initialize nodes
		File nodesDir = new File(topologyDir, "nodes");
		logger.assertLog(nodesDir.isDirectory(), nodesDir.getName() + " is not a directory!");
		File[] nodeXmls = nodesDir.listFiles(new FileFilter() {
			
			@Override
			public boolean accept(File pathname) {
				return pathname.getName().endsWith(".xml");
			}
		});
		logger.debug("Found " + nodeXmls.length + " nodes");
		this.nodesNumber = nodeXmls.length;
		nodes = new NodeType[nodesNumber];
		nodeRows = new Integer[nodesNumber];
		nodeColumns = new Integer[nodesNumber];
		try {
			this.hSize = Integer.valueOf(topologySize.substring(0, topologySize.lastIndexOf("x")));
		} catch (NumberFormatException e) {
			logger.fatal("Could not determine the size of the 2D mesh! Stopping...", e);
			System.exit(0);
		}
		for (int i = 0; i < nodeXmls.length; i++) {
			JAXBContext jaxbContext = JAXBContext
					.newInstance("ro.ulbsibiu.acaps.noc.xml.node");
			Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
			@SuppressWarnings("unchecked")
			NodeType node = ((JAXBElement<NodeType>) unmarshaller
					.unmarshal(nodeXmls[i])).getValue();
			
			node.setCore(Integer.toString(-1));
			node.setCost((double)switchEBit);
			nodes[Integer.valueOf(node.getId())] = node;
		}
		// initialize links
		File linksDir = new File(topologyDir, "links");
		logger.assertLog(linksDir.isDirectory(), linksDir.getName() + " is not a directory!");
		File[] linkXmls = linksDir.listFiles(new FileFilter() {
			
			@Override
			public boolean accept(File pathname) {
				return pathname.getName().endsWith(".xml");
			}
		});
		logger.debug("Found " + linkXmls.length + " links");
		this.linksNumber = linkXmls.length;
		links = new LinkType[linksNumber];
		for (int i = 0; i < linkXmls.length; i++) {
			JAXBContext jaxbContext = JAXBContext
					.newInstance("ro.ulbsibiu.acaps.noc.xml.link");
			Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
			@SuppressWarnings("unchecked")
			LinkType link = ((JAXBElement<LinkType>) unmarshaller
					.unmarshal(linkXmls[i])).getValue();
			
			link.setBandwidth(linkBandwidth);
			link.setCost((double)linkEBit);
			links[Integer.valueOf(link.getId())] = link;
		}

		// for each router generate a routing table provided by the XY routing
		// protocol
		routingTables = new int[nodesNumber][nodesNumber][nodesNumber];
		generateXYRoutingTable();

		generateLinkUsageList();
	}

	private void generateLinkUsageList() {
		if (this.buildRoutingTable == true) {
			linkUsageList = null;
		} else {
			// Allocate the space for the link usage table
			int[][][] linkUsageMatrix = new int[nodesNumber][nodesNumber][linksNumber];

			// Setting up the link usage matrix
			for (int srcId = 0; srcId < nodesNumber; srcId++) {
				for (int dstId = 0; dstId < nodesNumber; dstId++) {
					if (srcId == dstId) {
						continue;
					}
					NodeType currentNode = nodes[srcId];
					while (Integer.valueOf(currentNode.getId()) != dstId) {
						int linkId = routingTables[Integer.valueOf(currentNode.getId())][srcId][dstId];
						LinkType link = links[linkId];
						linkUsageMatrix[srcId][dstId][linkId] = 1;
						String node = "-1";
						// we work with with bidirectional links
						if (currentNode.getId().equals(link.getFirstNode())) {
							node = link.getSecondNode();
						} else {
							if (currentNode.getId().equals(link.getSecondNode())) {
								node = link.getFirstNode();
							}
						}
						currentNode = nodes[Integer.valueOf(node)];
					}
				}
			}

			// Now build the link usage list
			linkUsageList = new ArrayList[nodesNumber][nodesNumber];
			for (int src = 0; src < nodesNumber; src++) {
				for (int dst = 0; dst < nodesNumber; dst++) {
					linkUsageList[src][dst] = new ArrayList<Integer>();
					if (src == dst) {
						continue;
					}
					for (int linkId = 0; linkId < linksNumber; linkId++) {
						if (linkUsageMatrix[src][dst][linkId] == 1) {
							linkUsageList[src][dst].add(linkId);
						}
					}
				}
			}

			logger.assertLog(this.linkUsageList != null, null);
			logger.assertLog(linkUsageList.length == nodesNumber, null);
			for (int i = 0; i < linkUsageList.length; i++) {
				logger.assertLog(linkUsageList[i].length == nodesNumber, null);
			}
		}
	}

	/**
	 * Prints the current mapping
	 */
	private void printCurrentMapping() {
		for (int i = 0; i < nodesNumber; i++) {
			String apcg = "";
			if (!"-1".equals(nodes[i].getCore())) {
				apcg = cores[Integer.valueOf(nodes[i].getCore())].getApcgId();
			}
			System.out.println("NoC node " + nodes[i].getId() + " has core "
					+ nodes[i].getCore() + " (APCG " + apcg + ")");
		}
	}

	// TODO translate the following comment into English
	// (it describes how combinatorial arrangements of n elements taken as k can
	// be generated iteratively, in lexicographical order)
	//
	// generarea aranjamentelor in ordine
	// lexicografica printr-un procedeu iterativ. Se pleaca de la multimea
	// (vectorul) a=(1, 2, ...,
	// m).Fie un aranjament oarecare a=(a1, a2, ..., am). Pentru a se genera
	// succesorul
	// acestuia in ordine lexicografica se procedeaza astfel:
	// Se determina indicele i pentru care ai poate fi marit (cel mai mare
	// indice). Un element ai
	// nu poate fi marit daca valorile care sunt mai mari decit el respectiv
	// ai+1, ai+2, ..., n nu
	// sunt disponibile, adica apar pe alte pozitii in aranjament. Pentru a se
	// determina usor
	// elementele disponibile se introduce un vector DISP cu n elemente, astfel
	// incit DISP(i)
	// este 1 daca elemntul i apare in aranjamentul curent si 0 in caz contrar.
	// Se observa ca in momentul determinarii indicelui este necesar ca
	// elementul curent care
	// se doreste a fi marit trebuie sa se faca disponibil. Dupa ce acest
	// element a fost gasit,
	// acesta si elementele urmatoare se inlocuiesc cu cele mai mici numere
	// disponibile. In
	// cazul in care s-a ajuns la vectorul (n-m+1, ..., n-1, n) procesul de
	// generare al
	// aranjamentelor se opreste.
	// De exemplu, pentru n=5 si m=3 si a=(2 4 1) avem DISP=(0,0,1,0,1), iar
	// succesorii sai
	// sunt in ordine (2 4 1), (2 4 3), (2 4 5), (3 1 2), (3 1 4), s.a.m.d.
	
	private void init(int n, int[] a, boolean[] available) {
		for (int i = 0; i < a.length; i++) {
			a[i] = i;
			available[i] = false;
		}
		for (int i = a.length; i < n; i++) {
			available[i] = true;
		}
	}
	
	private boolean generate(int n, int[] a, boolean[] available) {
		int i = a.length - 1;
		boolean found = false;
		while (i >= 0 && !found) {
			available[a[i]] = true;
			int j = a[i] + 1;
			while (j < n && !found) {
				if (available[j]) {
					a[i] = j;
					available[j] = false;
					int k = 0;
					for (int l = i + 1; l < a.length; l++) {
						while(!available[k]) {
							k++;
						}
						a[l] = k;
						available[k] = false;
					}
					found = true;
				} else {
					j++;
				}
			}
			i--;
		}
		return found;
	}
	
	/**
	 * Computes n! / (n - k)!, where n is the number of NoC nodes and k is the
	 * number of cores. This number is the total number of possible mappings.
	 * 
	 * @param i
	 *            the nodes number
	 * @return #nodes! / (#nodes - #cores)!
	 */
	private long countPossibleMappings(int i) {
		long p = 1;
		if (i > nodesNumber - coresNumber) {
			p = i * countPossibleMappings(i - 1);
		}
		return p;
	}
	
	/**
	 * Generates all possible mappings
	 * 
	 * @param possibleMappings the number of possible mappings
	 * 
	 * @see #countPossibleMappings(int)
	 */
	private void searchExhaustively(long possibleMappings) {
		if (!buildRoutingTable) {
			linkBandwidthUsage = new int[linksNumber];
		} else {
			synLinkBandwithUsage = new int[hSize][nodes.length / hSize][4];
			generatedRoutingTable = new int[hSize][nodes.length / hSize][nodesNumber][nodesNumber];
			for (int i = 0; i < generatedRoutingTable.length; i++) {
				for (int j = 0; j < generatedRoutingTable[i].length; j++) {
					for (int k = 0; k < generatedRoutingTable[i][j].length; k++) {
						for (int l = 0; l < generatedRoutingTable[i][j][k].length; l++) {
							generatedRoutingTable[i][j][k][l] = -2;
						}
					}
				}
			}
		}

		boolean initialized = false;
		int[] a = new int[coresNumber];
		boolean[] available = new boolean[nodesNumber];
		boolean found = false;
		long counter = 0;
		final int STEP = 10;
		int stepCounter = 0;

		long userStart = 0;
		do {
			if (logger.isDebugEnabled()) {
				userStart = System.nanoTime();
			}
			if (!initialized) {
				init(nodesNumber, a, available);
				found = true;
				initialized = true;
			} else {
				found = generate(nodesNumber, a, available);
			}
			if (found) {
				counter++;
				if (logger.isDebugEnabled()) {
					logger.debug("Generated mapping number " + counter + " (of "
							+ possibleMappings + " possible mappings). "
							+ (counter * 100.0 / possibleMappings)
							+ "% of the entire search space currently explored.");
				} else {
					if (MathUtils.definitelyGreaterThan((float)(counter * 100.0 / possibleMappings), stepCounter)) {
						logger.info("Generated mapping number " + counter + " (of "
								+ possibleMappings + " possible mappings). "
								+ (counter * 100.0 / possibleMappings)
								+ "% of the entire search space currently explored.");
						stepCounter += STEP;
						logger.info("Next message will be show after a " + stepCounter + "% search progress");
					}
				}
				if (logger.isDebugEnabled()) {
					StringBuffer sb = new StringBuffer();
					for (int j = 0; j < a.length; j++) {
						sb.append(a[j] + " ");
					}
					logger.debug(sb);
				}
				
				for (int i = 0; i < nodes.length; i++) {
					nodes[i].setCore("-1");
				}
				for (int i = 0; i < cores.length; i++) {
					cores[i].setNodeId(-1);
				}
				for (int i = 0; i < a.length; i++) {
					nodes[a[i]].setCore(Integer.toString(i));
					cores[i].setNodeId(a[i]);
				}
				double cost = calculateTotalCost();
				if (MathUtils.definitelyLessThan((float) cost, (float) bestCost)) {
					bestCost = cost;
					bestMapping = new String[nodes.length];
					for (int i = 0; i < nodes.length; i++) {
						bestMapping[i] =  nodes[i].getCore();
					}
				}
			}
			if (logger.isDebugEnabled()) {
				long userEnd = System.nanoTime();
				logger.debug("Mapping generated in " + (userEnd - userStart) / 1.0e6 + " ms");
			}
		} while (found);
		
		for (int i = 0; i < nodes.length; i++) {
			nodes[i].setCore("-1");
		}
		for (int i = 0; i < cores.length; i++) {
			cores[i].setNodeId(-1);
		}
		for (int i = 0; i < nodes.length; i++) {
			nodes[i].setCore(bestMapping[i]);
			if (!"-1".equals(bestMapping[i])) {
				cores[Integer.valueOf(bestMapping[i])].setNodeId(i);
			}
		}
		
		if (buildRoutingTable) {
			programRouters();
		}
	}

	/**
	 * Swaps the processes from nodes with IDs t1 and t2
	 * 
	 * @param t1
	 *            the ID of the first node
	 * @param t2
	 *            the ID of the second node
	 */
	private void swapProcesses(int t1, int t2) {
		NodeType node1 = nodes[t1];
		NodeType node2 = nodes[t2];
		logger.assertLog(node1 != null, null);
		logger.assertLog(node2 != null, null);

		int p1 = Integer.valueOf(node1.getCore());
		int p2 = Integer.valueOf(node2.getCore());
		
		logger.assertLog(t1 == Integer.valueOf(node1.getId()), null);
		logger.assertLog(t2 == Integer.valueOf(node2.getId()), null);
		logger.assertLog(p1 == Integer.valueOf(node1.getCore()), null);
		logger.assertLog(p2 == Integer.valueOf(node2.getCore()), null);
		
		if (logger.isTraceEnabled()) {
			logger.trace("Swapping process " + p1 + " of node " + t1
					+ " with process " + p2 + " of node " + t2);
		}
		
		node1.setCore(Integer.toString(p2));
		node2.setCore(Integer.toString(p1));
		if (p1 != -1) {
			Core process = cores[p1];
			if (process == null) {
				process = new Core(p1, null, t2);
			} else {
				process.setNodeId(t2);
			}
		}
		if (p2 != -1) {
			Core process = cores[p2];
			if (process == null) {
				process = new Core(p2, null, t1);
			} else {
				process.setNodeId(t1);
			}
		}
	}

	/**
	 * Calculate the total cost in terms of the sum of the energy consumption
	 * and the penalty of the link overloading
	 * 
	 * @return the total cost
	 */
	private double calculateTotalCost() {
		// the communication energy part
		double energyCost = calculateCommunicationEnergy();
		float overloadCost;
		// now calculate the overloaded BW cost
		if (!buildRoutingTable) {
			overloadCost = calculateOverloadWithFixedRouting();
		} else {
			overloadCost = calculateOverloadWithAdaptiveRouting();
		}
		if (logger.isTraceEnabled()) {
			logger.trace("energy cost " + energyCost);
			logger.trace("overload cost " + overloadCost);
			logger.trace("total cost " + (energyCost + overloadCost));
		}
		return energyCost + overloadCost;
	}

	/**
	 * Computes the overload of the links when no routing is performed
	 * 
	 * @return the overload
	 */
	private float calculateOverloadWithFixedRouting() {
		Arrays.fill(linkBandwidthUsage, 0);
		for (int proc1 = 0; proc1 < coresNumber; proc1++) {
			for (int proc2 = proc1 + 1; proc2 < coresNumber; proc2++) {
				if (cores[proc1].getToBandwidthRequirement()[proc2] > 0) {
					int node1 = cores[proc1].getNodeId();
					int node2 = cores[proc2].getNodeId();
					for (int i = 0; i < linkUsageList[node1][node2].size(); i++) {
						int linkId = linkUsageList[node1][node2].get(i);
						linkBandwidthUsage[linkId] += cores[proc1]
								.getToBandwidthRequirement()[proc2];
					}
				}
				if (cores[proc1].getFromBandwidthRequirement()[proc2] > 0) {
					int node1 = cores[proc1].getNodeId();
					int node2 = cores[proc2].getNodeId();
					for (int i = 0; i < linkUsageList[node1][node2].size(); i++) {
						int linkId = linkUsageList[node2][node1].get(i);
						linkBandwidthUsage[linkId] += cores[proc1]
								.getFromBandwidthRequirement()[proc2];
					}
				}
			}
		}
		float overloadCost = 0;
		for (int i = 0; i < linksNumber; i++) {
			if (linkBandwidthUsage[i] > links[i].getBandwidth()) {
				overloadCost = ((float) linkBandwidthUsage[i])
						/ links[i].getBandwidth().floatValue() - 1.0f;
			}
		}
		overloadCost *= OVERLOAD_UNIT_COST;
		return overloadCost;
	}

	/**
	 * Computes the overload of the links when routing is performed
	 * 
	 * @return the overload
	 */
	private float calculateOverloadWithAdaptiveRouting() {
		float overloadCost = 0.0f;

		// Clear the link usage
		for (int i = 0; i < hSize; i++) {
			for (int j = 0; j < nodes.length / hSize; j++) {
				Arrays.fill(synLinkBandwithUsage[i][j], 0);
			}
		}

		for (int src = 0; src < coresNumber; src++) {
			for (int dst = 0; dst < coresNumber; dst++) {
				int node1 = cores[src].getNodeId();
				int node2 = cores[dst].getNodeId();
				if (cores[src].getToBandwidthRequirement()[dst] > 0) {
					routeTraffic(node1, node2,
							cores[src].getToBandwidthRequirement()[dst]);
				}
			}
		}

		for (int i = 0; i < hSize; i++) {
			for (int j = 0; j < nodes.length / hSize; j++) {
				for (int k = 0; k < 4; k++) {
					if (synLinkBandwithUsage[i][j][k] > links[0].getBandwidth()) {
						overloadCost += ((float) synLinkBandwithUsage[i][j][k])
								/ links[0].getBandwidth() - 1.0;
					}
				}
			}
		}

		overloadCost *= OVERLOAD_UNIT_COST;
		return overloadCost;
	}

	/**
	 * Routes the traffic. Hence, the routing table is computed here by the
	 * algorithm.
	 * 
	 * @param srcNode
	 *            the source node
	 * @param dstNode
	 *            the destination node
	 * @param bandwidth
	 *            the bandwidth
	 */
	private void routeTraffic(int srcNode, int dstNode, long bandwidth) {
		boolean commit = true;

		int srcRow = Integer.valueOf(getNodeTopologyParameter(nodes[srcNode], TopologyParameter.ROW));
		int srcColumn = Integer.valueOf(getNodeTopologyParameter(nodes[srcNode], TopologyParameter.COLUMN));
		int dstRow = Integer.valueOf(getNodeTopologyParameter(nodes[dstNode], TopologyParameter.ROW));
		int dstColumn = Integer.valueOf(getNodeTopologyParameter(nodes[dstNode], TopologyParameter.COLUMN));

		int row = srcRow;
		int col = srcColumn;

		int direction = -2;
		while (row != dstRow || col != dstColumn) {
			// For west-first routing
			if (LegalTurnSet.WEST_FIRST.equals(legalTurnSet)) {
				if (col > dstColumn) {
					// step west
					direction = WEST;
				} else {
					if (col == dstColumn) {
						direction = (row < dstRow) ? NORTH : SOUTH;
					} else {
						if (row == dstRow) {
							direction = EAST;
						}
						else {
							// Here comes the flexibility. We can choose whether to
							// go
							// vertical or horizontal
							int direction1 = (row < dstRow) ? NORTH : SOUTH;
							if (synLinkBandwithUsage[row][col][direction1] < synLinkBandwithUsage[row][col][EAST]) {
								direction = direction1;
							} else {
								if (synLinkBandwithUsage[row][col][direction1] > synLinkBandwithUsage[row][col][EAST]) {
									direction = EAST;
								} else {
									// In this case, we select the direction
									// which has the
									// longest
									// distance to the destination
									if ((dstColumn - col) * (dstColumn - col) <= (dstRow - row)
											* (dstRow - row)) {
										direction = direction1;
									} else {
										// Horizontal move
										direction = EAST;
									}
								}
							}
						}
					}
				}
			}
			// For odd-even routing
			else {
				if (LegalTurnSet.ODD_EVEN.equals(legalTurnSet)) {
					int e0 = dstColumn - col;
					int e1 = dstRow - row;
					if (e0 == 0) {
						// currently the same column as destination
						direction = (e1 > 0) ? NORTH : SOUTH;
					} else {
						if (e0 > 0) { // eastbound messages
							if (e1 == 0) {
								direction = EAST;
							} else {
								int direction1 = -1, direction2 = -1;
								if (col % 2 == 1 || col == srcColumn) {
									direction1 = (e1 > 0) ? NORTH : SOUTH;
								}
								if (dstColumn % 2 == 1 || e0 != 1) {
									direction2 = EAST;
								}
								if (direction1 == -1 && direction2 == -1) {
									logger.fatal("Error. Exiting...");
									System.exit(0);
								}
								if (direction1 == -1) {
									direction = direction2;
								} else {
									if (direction2 == -1) {
										direction = direction1;
									} else {
										// we have two choices
										direction = (synLinkBandwithUsage[row][col][direction1] < synLinkBandwithUsage[row][col][direction2]) ? direction1
												: direction2;
									}
								}
							}
						} else { // westbound messages
							if (col % 2 != 0 || e1 == 0) {
								direction = WEST;
							} else {
								int direction1 = (e1 > 0) ? NORTH : SOUTH;
								direction = (synLinkBandwithUsage[row][col][WEST] < synLinkBandwithUsage[row][col][direction1]) ? WEST
										: direction1;
							}
						}
					}
				}
			}
			synLinkBandwithUsage[row][col][direction] += bandwidth;

			if (commit) {
				generatedRoutingTable[row][col][srcNode][dstNode] = direction;
			}
			switch (direction) {
			case SOUTH:
				row--;
				break;
			case NORTH:
				row++;
				break;
			case EAST:
				col++;
				break;
			case WEST:
				col--;
				break;
			default:
				logger.error("Error. Unknown direction!");
				break;
			}
		}
	}

	private void programRouters() {
		// clean all the old routing table
		for (int nodeId = 0; nodeId < nodesNumber; nodeId++) {
			for (int srcNode = 0; srcNode < nodesNumber; srcNode++) {
				for (int dstNode = 0; dstNode < nodesNumber; dstNode++) {
					if (nodeId == dstNode) {
						routingTables[Integer.valueOf(nodes[nodeId].getId())][srcNode][dstNode] = -1;
					} else {
						routingTables[Integer.valueOf(nodes[nodeId].getId())][srcNode][dstNode] = -2;
					}
				}
			}
		}

		for (int row = 0; row < hSize; row++) {
			for (int col = 0; col < nodes.length / hSize; col++) {
				int nodeId = row * hSize + col;
				for (int srcNode = 0; srcNode < nodesNumber; srcNode++) {
					for (int dstNode = 0; dstNode < nodesNumber; dstNode++) {
						int linkId = locateLink(row, col,
								generatedRoutingTable[row][col][srcNode][dstNode]);
						if (linkId != -1) {
							routingTables[Integer.valueOf(nodes[nodeId].getId())][srcNode][dstNode] = linkId;
						}
					}
				}
			}
		}
	}

	/**
	 * Computes the communication energy
	 * 
	 * @return the communication energy
	 */
	private double calculateCommunicationEnergy() {
		double switchEnergy = calculateSwitchEnergy();
		double linkEnergy = calculateLinkEnergy();
		double bufferEnergy = calculateBufferEnergy();
		if (logger.isTraceEnabled()) {
			logger.trace("switch energy " + switchEnergy);
			logger.trace("link energy " + linkEnergy);
			logger.trace("buffer energy " + bufferEnergy);
		}
		return switchEnergy + linkEnergy + bufferEnergy;
	}

	private double calculateSwitchEnergy() {
		double energy = 0;
		for (int src = 0; src < nodesNumber; src++) {
			for (int dst = 0; dst < nodesNumber; dst++) {
				int srcProc = Integer.valueOf(nodes[src].getCore());
				int dstProc = Integer.valueOf(nodes[dst].getCore());
				if (srcProc > -1 && dstProc > -1) {
					long commVol = cores[srcProc].getToCommunication()[dstProc];
					if (commVol > 0) {
						energy += nodes[src].getCost() * commVol;
						NodeType currentNode = nodes[src];
						if (logger.isTraceEnabled()) {
							logger.trace("adding " + currentNode.getCost() + " * "
									+ commVol + " (core " + srcProc + " to core "
									+ dstProc + ") current node "
									+ currentNode.getId());
						}
						while (Integer.valueOf(currentNode.getId()) != dst) {
							int linkId = routingTables[Integer.valueOf(currentNode.getId())][src][dst];
							LinkType link = links[linkId];
							String node = "-1";
							// we work with with bidirectional links
							if (currentNode.getId().equals(link.getFirstNode())) {
								node = link.getSecondNode();
							} else {
								if (currentNode.getId().equals(link.getSecondNode())) {
									node = link.getFirstNode();
								}
							}
							currentNode = nodes[Integer.valueOf(node)];
							energy += currentNode.getCost() * commVol;
							if (logger.isTraceEnabled()) {
								logger.trace("adding " + currentNode.getCost()
										+ " * " + commVol + " (core " + srcProc
										+ " to core " + dstProc + ") current node "
										+ currentNode.getId() + " link ID "
										+ linkId);
							}
						}
					}
				}
			}
		}
		return energy;
	}

	private double calculateLinkEnergy() {
		double energy = 0;
		for (int src = 0; src < nodesNumber; src++) {
			for (int dst = 0; dst < nodesNumber; dst++) {
				int srcProc = Integer.valueOf(nodes[src].getCore());
				int dstProc = Integer.valueOf(nodes[dst].getCore());
				if (srcProc > -1 && dstProc > -1) {
					long commVol = cores[srcProc].getToCommunication()[dstProc];
					if (commVol > 0) {
						NodeType currentNode = nodes[src];
						while (Integer.valueOf(currentNode.getId()) != dst) {
							int linkId = routingTables[Integer.valueOf(currentNode.getId())][src][dst];
							energy += links[linkId].getCost() * commVol;
							LinkType link = links[linkId];
							String node = "-1";
							// we work with with bidirectional links
							if (currentNode.getId().equals(link.getFirstNode())) {
								node = link.getSecondNode();
							} else {
								if (currentNode.getId().equals(link.getSecondNode())) {
									node = link.getFirstNode();
								}
							}
							currentNode = nodes[Integer.valueOf(node)];
						}
					}
				}
			}
		}
		return energy;
	}

	private double calculateBufferEnergy() {
		double energy = 0;
		for (int src = 0; src < nodesNumber; src++) {
			for (int dst = 0; dst < nodesNumber; dst++) {
				int srcProc = Integer.valueOf(nodes[src].getCore());
				int dstProc = Integer.valueOf(nodes[dst].getCore());
				if (srcProc > -1 && dstProc > -1) {
					long commVol = cores[srcProc].getToCommunication()[dstProc];
					if (commVol > 0) {
						NodeType currentNode = nodes[src];
						while (Integer.valueOf(currentNode.getId()) != dst) {
							int linkId = routingTables[Integer.valueOf(currentNode.getId())][src][dst];
							energy += (bufReadEBit + bufWriteEBit) * commVol;
							LinkType link = links[linkId];
							String node = "-1";
							// we work with with bidirectional links
							if (currentNode.getId().equals(link.getFirstNode())) {
								node = link.getSecondNode();
							} else {
								if (currentNode.getId().equals(link.getSecondNode())) {
									node = link.getFirstNode();
								}
							}
							currentNode = nodes[Integer.valueOf(node)];
						}
						energy += bufWriteEBit * commVol;
					}
				}
			}
		}
		return energy;
	}

	/**
	 * find out the link ID. If the direction is not set, return -1
	 * 
	 * @param row
	 *            the row from the 2D mesh
	 * @param column
	 *            the column form the 2D mesh
	 * @param direction
	 *            the direction
	 * @return the link ID
	 */
	private int locateLink(int row, int column, int direction) {
		int origRow = row;
		int origColumn = column;
		switch (direction) {
		case NORTH:
			row++;
			break;
		case SOUTH:
			row--;
			break;
		case EAST:
			column++;
			break;
		case WEST:
			column--;
			break;
		default:
			return -1;
		}
		int linkId;
		for (linkId = 0; linkId < linksNumber; linkId++) {
			if (Integer.valueOf(getNodeTopologyParameter(nodes[Integer.valueOf(links[linkId].getFirstNode())], TopologyParameter.ROW)) == origRow
					&& Integer.valueOf(getNodeTopologyParameter(nodes[Integer.valueOf(links[linkId].getFirstNode())], TopologyParameter.COLUMN)) == origColumn
					&& Integer.valueOf(getNodeTopologyParameter(nodes[Integer.valueOf(links[linkId].getSecondNode())], TopologyParameter.ROW)) == row
					&& Integer.valueOf(getNodeTopologyParameter(nodes[Integer.valueOf(links[linkId].getSecondNode())], TopologyParameter.COLUMN)) == column)
				break;
			if (Integer.valueOf(getNodeTopologyParameter(nodes[Integer.valueOf(links[linkId].getSecondNode())], TopologyParameter.ROW)) == origRow
					&& Integer.valueOf(getNodeTopologyParameter(nodes[Integer.valueOf(links[linkId].getSecondNode())], TopologyParameter.COLUMN)) == origColumn
					&& Integer.valueOf(getNodeTopologyParameter(nodes[Integer.valueOf(links[linkId].getFirstNode())], TopologyParameter.ROW)) == row
					&& Integer.valueOf(getNodeTopologyParameter(nodes[Integer.valueOf(links[linkId].getFirstNode())], TopologyParameter.COLUMN)) == column)
				break;
		}
		if (linkId == linksNumber) {
			logger.fatal("Error in locating link");
			System.exit(-1);
		}
		return linkId;
	}

	private boolean verifyBandwidthRequirement() {
		generateLinkUsageList();

		int[] usedBandwidth = new int[linksNumber];
		
		for (int i = 0; i < linksNumber; i++) {
			usedBandwidth[i] = 0;
		}

		for (int src = 0; src < nodesNumber; src++) {
			for (int dst = 0; dst < nodesNumber; dst++) {
	            if (src == dst) {
	                continue;
	            }
	            int srcProc = Integer.valueOf(nodes[src].getCore());
	            int dstProc = Integer.valueOf(nodes[dst].getCore());
	            if (srcProc > -1 && dstProc > -1) {
	            	long commLoad = cores[srcProc].getToBandwidthRequirement()[dstProc];
		            if (commLoad == 0) {
		                continue;
		            }
		            NodeType currentNode = nodes[src];
		            while (Integer.valueOf(currentNode.getId()) != dst) {
		                int linkId = routingTables[Integer.valueOf(currentNode.getId())][src][dst];
		                LinkType link = links[linkId];
						String node = "-1";
						// we work with with bidirectional links
						if (currentNode.getId().equals(link.getFirstNode())) {
							node = link.getSecondNode();
						} else {
							if (currentNode.getId().equals(link.getSecondNode())) {
								node = link.getFirstNode();
							}
						}
						currentNode = nodes[Integer.valueOf(node)];
		                usedBandwidth[linkId] += commLoad;
		            }
	            }
	        }
	    }
	    //check for the overloaded links
	    int violations = 0;
		for (int i = 0; i < linksNumber; i++) {
	        if (usedBandwidth[i] > links[i].getBandwidth()) {
	        	System.out.println("Link " + i + " is overloaded: " + usedBandwidth[i] + " > "
	                 + links[i].getBandwidth());
	            violations ++;
	        }
	    }
		return violations == 0;
	}
	
	/**
	 * Performs an analysis of the mapping. It verifies if bandwidth
	 * requirements are met and computes the link, switch and buffer energy.
	 * The communication energy is also computed (as a sum of the three energy
	 * components).
	 */
	public void analyzeIt() {
	    logger.info("Verify the communication load of each link...");
	    String bandwidthRequirements;
	    if (verifyBandwidthRequirement()) {
	    	logger.info("Succes");
	    	bandwidthRequirements = "Succes";
	    }
	    else {
	    	logger.info("Fail");
	    	bandwidthRequirements = "Fail";
	    }
	    if (logger.isDebugEnabled()) {
		    logger.debug("Energy consumption estimation ");
		    logger.debug("(note that this is not exact numbers, but serve as a relative energy indication) ");
		    logger.debug("Energy consumed in link is " + calculateLinkEnergy());
		    logger.debug("Energy consumed in switch is " + calculateSwitchEnergy());
		    logger.debug("Energy consumed in buffer is " + calculateBufferEnergy());
	    }
	    double energy = calculateCommunicationEnergy();
	    logger.info("Total communication energy consumption is " + energy);
	    
		MapperDatabase.getInstance().setOutputs(
				new String[] { "bandwidthRequirements", "energy" },
				new String[] { bandwidthRequirements, Double.toString(energy) });
	}
	
	private void saveRoutingTables() {
		if (logger.isInfoEnabled()) {
			logger.info("Saving the routing tables");
		}
		
		for (int i = 0; i < nodes.length; i++) {
			int[][] routingEntries = routingTables[Integer.valueOf(nodes[i].getId())];
			for (int j = 0; j < routingEntries.length; j++) {
				for (int k = 0; k < routingEntries[j].length; k++) {
					if (routingEntries[j][k] >= 0) {
						RoutingTableEntryType routingTableEntry = new RoutingTableEntryType();
						routingTableEntry.setSource(Integer.toString(j));
						routingTableEntry.setDestination(Integer.toString(k));
						routingTableEntry.setLink(Integer.toString(routingEntries[j][k]));
						nodes[i].getRoutingTableEntry().add(routingTableEntry);
					}
				}
			}
		}
	}
	
	private void saveTopology() {
		try {
			// save the nodes
			JAXBContext jaxbContext = JAXBContext.newInstance(NodeType.class);
			Marshaller marshaller = jaxbContext.createMarshaller();
			marshaller.setProperty("jaxb.formatted.output", Boolean.TRUE);
			ObjectFactory nodeFactory = new ObjectFactory();
			for (int i = 0; i < nodes.length; i++) {
				StringWriter stringWriter = new StringWriter();
				JAXBElement<NodeType> node = nodeFactory.createNode(nodes[i]);
				marshaller.marshal(node, stringWriter);	
				String routing = "";
				if (buildRoutingTable) {
					routing = "_routing";
				}
				File file = new File(topologyDir + File.separator + "sa" + routing
						+ File.separator + "nodes");
				file.mkdirs();
				PrintWriter pw = new PrintWriter(file + File.separator
						+ "node-" + i + ".xml");
				logger.debug("Saving the XML for node " + i);
				pw.write(stringWriter.toString());
				pw.close();
			}
			// save the links
			jaxbContext = JAXBContext.newInstance(LinkType.class);
			marshaller = jaxbContext.createMarshaller();
			marshaller.setProperty("jaxb.formatted.output", Boolean.TRUE);
			ro.ulbsibiu.acaps.noc.xml.link.ObjectFactory linkFactory = new ro.ulbsibiu.acaps.noc.xml.link.ObjectFactory();
			for (int i = 0; i < links.length; i++) {
				StringWriter stringWriter = new StringWriter();
				JAXBElement<LinkType> link = linkFactory.createLink(links[i]);
				marshaller.marshal(link, stringWriter);
				String routing = "";
				if (buildRoutingTable) {
					routing = "_routing";
				}
				File file = new File(topologyDir + File.separator + "sa" + routing
						+ File.separator + "links");
				file.mkdirs();
				PrintWriter pw = new PrintWriter(file + File.separator
						+ "link-" + i + ".xml");
				logger.debug("Saving the XML for link " + i);
				pw.write(stringWriter.toString());
				pw.close();
			}
		} catch (JAXBException e) {
			logger.error("JAXB encountered an error", e);
		} catch (FileNotFoundException e) {
			logger.error("File not found", e);
		}
	}
	
	@Override
	public String map() throws TooFewNocNodesException {
		if (nodesNumber < coresNumber) {
			throw new TooFewNocNodesException(coresNumber, nodesNumber);
		}

		long possibleMappings = countPossibleMappings(nodesNumber);
		logger.info("This search space contains " + nodesNumber + "! / " + "("
				+ nodesNumber + " - " + coresNumber + ")! = "
				+ possibleMappings + " possible mappings!");

		if (coresNumber == 1) {
			logger.info("Exhaustive Search will not start for mapping a single core. This core simply mapped randomly.");
		} else {
			logger.info("Start mapping...");

			logger.assertLog((coresNumber == ((int) cores.length)), null);

		}
		Date startDate = new Date();
		HeapUsageMonitor monitor = new HeapUsageMonitor();
		monitor.startMonitor();
		long userStart = TimeUtils.getUserTime();
		long sysStart = TimeUtils.getSystemTime();
		long realStart = System.nanoTime();
		
		if (coresNumber > 1) {
			searchExhaustively(possibleMappings);
		}
		
		long userEnd = TimeUtils.getUserTime();
		long sysEnd = TimeUtils.getSystemTime();
		long realEnd = System.nanoTime();
		monitor.stopMonitor();
		logger.info("Mapping process finished successfully.");
		logger.info("Time: " + (realEnd - realStart) / 1e9 + " seconds");
		logger.info("Memory: " + monitor.getAverageUsedHeap()
				/ (1024 * 1024 * 1.0) + " MB");
		
		saveRoutingTables();
		
		saveTopology();

		MappingType mapping = new MappingType();
		mapping.setId(MAPPER_ID);
		mapping.setRuntime(new Double(realEnd - realStart));
		for (int i = 0; i < nodes.length; i++) {
			if (!"-1".equals(nodes[i].getCore())) {
				MapType map = new MapType();
				map.setNode(nodes[i].getId());
				map.setCore(Integer.toString(cores[Integer.parseInt(nodes[i].getCore())].getCoreId()));
				map.setApcg(cores[Integer.parseInt(nodes[i].getCore())].getApcgId());
				mapping.getMap().add(map);
			}
		}
		StringWriter stringWriter = new StringWriter();
		ro.ulbsibiu.acaps.ctg.xml.mapping.ObjectFactory mappingFactory = new ro.ulbsibiu.acaps.ctg.xml.mapping.ObjectFactory();
		JAXBElement<MappingType> jaxbElement = mappingFactory.createMapping(mapping);
		try {
			JAXBContext jaxbContext = JAXBContext.newInstance(MappingType.class);
			Marshaller marshaller = jaxbContext.createMarshaller();
			marshaller.setProperty("jaxb.formatted.output", Boolean.TRUE);
			marshaller.marshal(jaxbElement, stringWriter);
		} catch (JAXBException e) {
			logger.error("JAXB encountered an error", e);
		}
		
		int benchmarkId = MapperDatabase.getInstance().getBenchmarkId(benchmarkName, ctgId);
		int nocTopologyId = MapperDatabase.getInstance().getNocTopologyId(topologyName, topologySize);
		MapperDatabase.getInstance().saveMapping(getMapperId(),
				"Exhaustive Search", benchmarkId, apcgId, nocTopologyId,
				stringWriter.toString(), startDate,
				(realEnd - realStart) / 1e9, (userEnd - userStart) / 1e9,
				(sysEnd - sysStart) / 1e9, monitor.getAverageUsedHeap(), null);

		return stringWriter.toString();
	}

	private void parseTrafficConfig(String filePath, double linkBandwidth)
			throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(
				new FileInputStream(filePath)));

		int id = -1;

		String line;
		while ((line = br.readLine()) != null) {
			// line starting with "#" are comments
			if (line.startsWith("#")) {
				continue;
			}

			if (line.contains("@NODE")) {
				try {
					id = Integer.valueOf(line.substring("@NODE".length() + 1));
				} catch (NumberFormatException e) {
					logger.error("The node from line '" + line + "' is not a number", e);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("ID = " + id);
				}
			}

			if (line.contains("packet_to_destination_rate")) {
				String substring = line.substring(
						"packet_to_destination_rate".length() + 1).trim();
				int dstId = -1;
				try {
					dstId = Integer.valueOf(substring.substring(0,
							substring.indexOf("\t")));
					if (logger.isTraceEnabled()) {
						logger.trace(" dst ID = " + dstId);
					}
				} catch (NumberFormatException e) {
					logger.error("The destination from line '" + line + "' is not a number", e);
				}
				double rate = 0;
				try {
					rate = Double.valueOf(substring.substring(substring
							.indexOf("\t") + 1));
					if (logger.isTraceEnabled()) {
						logger.trace(" rate = " + rate);
					}
				} catch (NumberFormatException e) {
					logger.error("The rate from line '" + line + "' is not a number", e);
				}

				if (rate > 1) {
					logger.fatal("Invalid rate!");
					System.exit(0);
				}
				cores[id].getToCommunication()[dstId] = (int) (rate * 1000000);
				cores[id].getToBandwidthRequirement()[dstId] = (int) (rate * 3 * linkBandwidth);
				cores[dstId].getFromCommunication()[id] = (int) (rate * 1000000);
				cores[dstId].getFromBandwidthRequirement()[id] = (int) (rate * 3 * linkBandwidth);
			}
		}

		br.close();
	}

	public static void main(String[] args) throws TooFewNocNodesException,
			IOException, JAXBException {
		final int applicationBandwithRequirement = 3; // a multiple of the communication volume
		final double linkBandwidth = 256E9;
		final float switchEBit = 0.284f;
		final float linkEBit = 0.449f;
		final float bufReadEBit = 1.056f;
		final float bufWriteEBit = 2.831f;
		
		MapperInputProcessor mapperInputProcessor = new MapperInputProcessor() {
			
			@Override
			public void useMapper(String benchmarkFilePath, String benchmarkName,
					String ctgId, String apcgId, List<CtgType> ctgTypes,
					List<ApcgType> apcgTypes, boolean doRouting, Long seed) throws JAXBException,
					TooFewNocNodesException, FileNotFoundException {
				logger.info("Using an Exhaustive search mapper for "
						+ benchmarkFilePath + "ctg-" + ctgId + " (APCG " + apcgId + ")");
				
				ExhaustiveSearchMapper esMapper;
				int cores = 0;
				for (int k = 0; k < apcgTypes.size(); k++) {
					cores += apcgTypes.get(k).getCore().size();
				}
				int hSize = (int) Math.ceil(Math.sqrt(cores));
				hSize = Math.max(2, hSize); // using at least a 2x2 2D mesh
				String meshSize;
				// we allow rectangular 2D meshes as well
				if (hSize * (hSize - 1) >= cores) {
					meshSize = hSize + "x" + (hSize - 1);
				} else {
					meshSize = hSize + "x" + hSize;
				}
				logger.info("The algorithm has " + cores + " cores to map => working with a 2D mesh of size " + meshSize);
				// working with a 2D mesh topology
				String topologyName = "mesh2D";
				String topologyDir = ".." + File.separator + "NoC-XML"
						+ File.separator + "src" + File.separator
						+ "ro" + File.separator + "ulbsibiu"
						+ File.separator + "acaps" + File.separator
						+ "noc" + File.separator + "topology"
						+ File.separator + topologyName + File.separator
						+ meshSize;
				
				String[] parameters = new String[] {
						"applicationBandwithRequirement",
						"linkBandwidth",
						"switchEBit",
						"linkEBit",
						"bufReadEBit",
						"bufWriteEBit",
						"routing"};
				String values[] = new String[] {
						Integer.toString(applicationBandwithRequirement),
						Double.toString(linkBandwidth),
						Float.toString(switchEBit), Float.toString(linkEBit),
						Float.toString(bufReadEBit),
						Float.toString(bufWriteEBit),
						null};
				if (doRouting) {
					values[values.length - 1] = "true";
					MapperDatabase.getInstance().setParameters(parameters, values);
					
					// with routing
					esMapper = new ExhaustiveSearchMapper(
							benchmarkName, ctgId, apcgId,
							topologyName, meshSize, new File(
									topologyDir), cores, linkBandwidth,
							true, LegalTurnSet.ODD_EVEN, bufReadEBit,
							bufWriteEBit, switchEBit, linkEBit);
				} else {
					values[values.length - 1] = "false";
					MapperDatabase.getInstance().setParameters(parameters, values);
					
					// without routing
					esMapper = new ExhaustiveSearchMapper(
							benchmarkName, ctgId, apcgId,
							topologyName, meshSize, new File(
									topologyDir), cores, linkBandwidth,
							switchEBit, linkEBit);
				}
	
	//			// read the input data from a traffic.config file (NoCmap style)
	//			saMapper(
	//					"telecom-mocsyn-16tile-selectedpe.traffic.config",
	//					linkBandwidth);
				
				for (int k = 0; k < apcgTypes.size(); k++) {
					// read the input data using the Unified Framework's XML interface
					esMapper.parseApcg(apcgTypes.get(k), ctgTypes.get(k), applicationBandwithRequirement);
				}
				
	//			// This is just for checking that bbMapper.parseTrafficConfig(...)
	//			// and parseApcg(...) have the same effect
	//			bbMapper.printCores();
	
				String mappingXml = esMapper.map();
				File dir = new File(benchmarkFilePath + "ctg-" + ctgId);
				dir.mkdirs();
				String routing = "";
				if (doRouting) {
					routing = "_routing";
				}
				String mappingXmlFilePath = benchmarkFilePath + "ctg-" + ctgId
						+ File.separator + "mapping-" + apcgId + "_"
						+ esMapper.getMapperId() + routing + ".xml";
				PrintWriter pw = new PrintWriter(mappingXmlFilePath);
				logger.info("Saving the mapping XML file" + mappingXmlFilePath);
				pw.write(mappingXml);
				pw.close();
	
				logger.info("The generated mapping is:");
				esMapper.printCurrentMapping();
				
				esMapper.analyzeIt();
				
			}
		};
		mapperInputProcessor.processInput(args);
	}
}
