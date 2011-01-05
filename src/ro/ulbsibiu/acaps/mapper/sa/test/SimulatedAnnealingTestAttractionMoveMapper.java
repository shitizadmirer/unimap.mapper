package ro.ulbsibiu.acaps.mapper.sa.test;

import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.JAXBException;

import ro.ulbsibiu.acaps.ctg.xml.apcg.ApcgType;
import ro.ulbsibiu.acaps.ctg.xml.ctg.CtgType;
import ro.ulbsibiu.acaps.mapper.MapperDatabase;
import ro.ulbsibiu.acaps.mapper.TooFewNocNodesException;
import ro.ulbsibiu.acaps.mapper.util.MapperInputProcessor;
import ro.ulbsibiu.acaps.mapper.util.MathUtils;

/**
 * {@link SimulatedAnnealingTestMapper} that replaces the random swapping with
 * an attraction move.
 * 
 * @see SimulatedAnnealingTestMapper
 * 
 * @author cipi
 * 
 */
public class SimulatedAnnealingTestAttractionMoveMapper extends
		SimulatedAnnealingTestMapper {
	
	/**
	 * Logger for this class
	 */
	private static final Logger logger = Logger
			.getLogger(SimulatedAnnealingTestAttractionMoveMapper.class);

	public SimulatedAnnealingTestAttractionMoveMapper(String benchmarkName,
			String ctgId, String apcgId, String topologyName,
			String topologySize, File topologyDir, int coresNumber,
			double linkBandwidth, boolean buildRoutingTable,
			LegalTurnSet legalTurnSet, float bufReadEBit, float bufWriteEBit,
			float switchEBit, float linkEBit) throws JAXBException {
		super(benchmarkName, ctgId, apcgId, topologyName, topologySize,
				topologyDir, coresNumber, linkBandwidth, buildRoutingTable,
				legalTurnSet, bufReadEBit, bufWriteEBit, switchEBit, linkEBit);
	}

	public SimulatedAnnealingTestAttractionMoveMapper(String benchmarkName,
			String ctgId, String apcgId, String topologyName,
			String topologySize, File topologyDir, int coresNumber,
			double linkBandwidth, float switchEBit, float linkEBit)
			throws JAXBException {
		super(benchmarkName, ctgId, apcgId, topologyName, topologySize,
				topologyDir, coresNumber, linkBandwidth, switchEBit, linkEBit);
	}

	@Override
	public String getMapperId() {
		return super.getMapperId() + "-" + "attraction_move";
	}

	@Override
	protected int[] move() {
		return makeAttractionMove();
	}

	/**
	 * The core selected to be moved (let it be c1) is placed next to one of the
	 * cores to which it communicates the most (let it be c2). Core c1 is
	 * selected using {@link #selectCore()}. Core c2 is selected in a similar
	 * manner. {@link #selectCore()} accounts only for the to communications
	 * when building the probability distribution function. For the selection of
	 * core c2, both to and from communications are considered.
	 * <p>
	 * Core c1 is placed onto one of core c2's node neighbors, chosen uniformly
	 * random.
	 * </p>
	 * <p>
	 * This kind of move tries to make communicating cores to attract each
	 * other, to cluster themselves in a natural manner.
	 * </p>
	 * 
	 * @return the two nodes to be swapped (a two elements array)
	 */
	private int[] makeAttractionMove() {
		int node1;
		int node2;

		// do {
		// node1 = (int) uniformIntegerRandomVariable(0, nodesNumber - 1);
		// } while ("-1".equals(nodes[node1].getCore()));
		//
		// int core1 = Integer.valueOf(nodes[node1].getCore());

		int core1 = selectCore();
		if (core1 == -1) {
			logger.fatal("Unable to select any core for moving!");
			System.exit(-1);
		}
		node1 = cores[core1].getNodeId();

		if (logger.isDebugEnabled()) {
			logger.debug("Selected node " + node1 + " for moving. It has core "
					+ core1);
			logger.debug("Node " + node1 + " communicates with nodes "
					+ nodeNeighbors[node1]);
			logger.debug("Core " + core1 + " communicates with cores "
					+ coreNeighbors[core1]);
		}

		double[] core1CommunicationPDF = coresCommunicationPDF[core1];
		int core2 = -1;
		double p = uniformRandomVariable();
		double sum = 0;
		for (int i = 0; i < core1CommunicationPDF.length; i++) {
			sum += core1CommunicationPDF[i];
			if (MathUtils.definitelyLessThan((float) p, (float) sum)
					|| MathUtils.approximatelyEqual((float) p, (float) sum)) {
				core2 = i;
				break; // essential!
			}
		}
		if (core2 == -1) {
			logger.fatal("Unable to determine a core with which core " + core1
					+ " will swap");
		}
		int core2Node = cores[core2].getNodeId();
		List<Integer> core1AllowedNodes = new ArrayList<Integer>(
				nodeNeighbors[core2Node]);
		core1AllowedNodes.remove(new Integer(node1));

		if (core1AllowedNodes.size() == 0) {
			node2 = node1;
			logger.warn("No nodes are allowed for core " + core1
					+ ". We pretend we make a move by swapping node " + node1
					+ " with node " + node2);
		} else {
			int i = (int) uniformIntegerRandomVariable(0,
					core1AllowedNodes.size() - 1);
			node2 = core1AllowedNodes.get(i);

			// node2 = -1;
			// double[] core2CommunicationPDF = coresCommunicationPDF[core2];
			// double min = Float.MAX_VALUE; // needs to be float, not double
			// for (int i = 0; i < core1AllowedNodes.size(); i++) {
			// Integer core1AllowedNode = core1AllowedNodes.get(i);
			// String core = nodes[core1AllowedNode].getCore();
			// if (MathUtils.definitelyLessThan(
			// (float) core2CommunicationPDF[cores[Integer
			// .valueOf(core)].getCoreId()], (float) min)) {
			// min = core2CommunicationPDF[cores[Integer.valueOf(core)]
			// .getCoreId()];
			// node2 = core1AllowedNode;
			// }
			// }

			if (logger.isDebugEnabled()) {
				logger.debug("Core " + core1 + " will be moved from node "
						+ node1 + " to the allowed node " + node2);
			}
		}
		logger.assertLog(
				node1 != -1 && node2 != -1 && node1 != node2,
				"At least one node is not defined (i.e. = -1) or the two nodes are identical; node1 = "
						+ node1 + ", node2 = " + node2);
		if (logger.isDebugEnabled()) {
			logger.debug("Swapping nodes " + node1 + " and " + node2);
		}
		swapProcesses(node1, node2);

		return new int[] { node1, node2 };
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
					List<ApcgType> apcgTypes, boolean doRouting) throws JAXBException,
					TooFewNocNodesException, FileNotFoundException {
				logger.info("Using a Simulated annealing mapper for "
						+ benchmarkFilePath + "ctg-" + ctgId + " (APCG " + apcgId + ")");
				
				SimulatedAnnealingTestMapper saMapper;
				int cores = 0;
				for (int k = 0; k < apcgTypes.size(); k++) {
					cores += apcgTypes.get(k).getCore().size();
				}
				int hSize = (int) Math.ceil(Math.sqrt(cores));
				hSize = Math.max(2, hSize); // using at least a 2x2 2D mesh
				String meshSize = hSize + "x" + hSize;
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
					
					// SA with routing
					saMapper = new SimulatedAnnealingTestAttractionMoveMapper(
							benchmarkName, ctgId, apcgId,
							topologyName, meshSize, new File(
									topologyDir), cores, linkBandwidth,
							true, LegalTurnSet.ODD_EVEN, bufReadEBit,
							bufWriteEBit, switchEBit, linkEBit);
				} else {
					values[values.length - 1] = "false";
					MapperDatabase.getInstance().setParameters(parameters, values);
					
					// SA without routing
					saMapper = new SimulatedAnnealingTestAttractionMoveMapper(
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
					saMapper.parseApcg(apcgTypes.get(k), ctgTypes.get(k), applicationBandwithRequirement);
				}
				
		//			// This is just for checking that bbMapper.parseTrafficConfig(...)
		//			// and parseApcg(...) have the same effect
		//			bbMapper.printCores();
		
				String mappingXml = saMapper.map();
				File dir = new File(benchmarkFilePath + "ctg-" + ctgId);
				dir.mkdirs();
				String routing = "";
				if (doRouting) {
					routing = "_routing";
				}
				String mappingXmlFilePath = benchmarkFilePath + "ctg-" + ctgId
						+ File.separator + "mapping-" + apcgId + "_"
						+ saMapper.getMapperId() + routing + ".xml";
				PrintWriter pw = new PrintWriter(mappingXmlFilePath);
				logger.info("Saving the mapping XML file" + mappingXmlFilePath);
				pw.write(mappingXml);
				pw.close();
		
				logger.info("The generated mapping is:");
				saMapper.printCurrentMapping();
				
				saMapper.analyzeIt();
			}
		};
		mapperInputProcessor.processInput(args);
	}

}