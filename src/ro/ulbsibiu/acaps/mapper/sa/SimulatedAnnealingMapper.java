package ro.ulbsibiu.acaps.mapper.sa;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import ro.ulbsibiu.acaps.mapper.Mapper;
import ro.ulbsibiu.acaps.mapper.TooFewNocNodesException;
import ro.ulbsibiu.acaps.mapper.util.MathUtils;

/**
 * Simulated Annealing algorithm for Network-on-Chip (NoC) application mapping.
 * The implementation is based on the one from <a
 * href="http://www.ece.cmu.edu/~sld/wiki/doku.php?id=shared:nocmap">NoCMap</a>
 * 
 * <p>
 * Note that currently, this algorithm works only with N x N 2D mesh NoCs
 * </p>
 * 
 * @author cipi
 * 
 */
public class SimulatedAnnealingMapper implements Mapper {

	private static final int NORTH = 0;

	private static final int SOUTH = 1;

	private static final int EAST = 2;

	private static final int WEST = 3;

	/** tolerance for the cost function of the algorithm */
	private static final int TOLERANCE = 1;

	/** how many temperature variations the algorithm should try */
	private static final int TEMPS = 5;

	/**
	 * the minimum acceptance ratio (number of viable IP core mappings vs. the
	 * total number of tried mappings)
	 */
	private static final double MINACCEPT = 0.001;

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

	/** energy consumption per bit read */
	private float bufReadEBit;

	/** energy consumption per bit write */
	private float bufWriteEBit;

	/** the number of tiles (nodes) from the NoC */
	private int gTileNum;

	/**
	 * the size of the 2D mesh, sqrt(gTileNum) (sqrt(gTileNum) * sqrt(gTileNum)
	 * = gTileNum)
	 */
	private int gEdgeSize;

	/**
	 * the number of processes (tasks). Note that each core has only one task
	 * associated to it.
	 */
	private int gProcNum;

	/**
	 * the number of links from the NoC
	 */
	private int gLinkNum;

	/** the tiles from the Network-on-Chip (NoC) */
	private Tile[] gTile;

	/** the processes (tasks, cores) */
	private Process[] gProcess;

	/** the communication channels from the NoC */
	private Link[] gLink;

	/**
	 * what links are used by tiles to communicate (each source - destination
	 * tile pair has a list of link IDs). The matrix must have size
	 * <tt>gTileNum x gTileNum</tt>. <b>This must be <tt>null</tt> when
	 * <tt>buildRoutingTable</tt> is <tt>true</tt> </b>
	 */
	private List<Integer>[][] linkUsageList = null;

	/** the seed for the random number generator */
	private int seed;

	/**
	 * how many mapping attempts the algorithm tries per iteration. A mapping
	 * attempt means a random swap of processes (tasks) between to network tiles
	 */
	private int attempts;

	/** how many zero cost mappings where accepted */
	private int zeroCostAcceptance;

	/**
	 * specifies if the algorithm must be stopped "manually". This is typically
	 * done when the zero cost acceptance is 10
	 */
	private boolean needStop;

	/** the cost of the current mapping */
	private double currentCost;

	/** the acceptance ratio */
	private double acceptRatio;

	/** how many zero cost mappings are currently accepted */
	private int zeroTempCnt = 0;

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
	private int[][][][] saRoutingTable = null;

	/**
	 * Default constructor
	 * <p>
	 * No routing table is built.
	 * </p>
	 * 
	 * @param gTileNum
	 *            the size of the 2D mesh (gTileNum * gTileNum)
	 * @param gProcNum
	 *            the number of processes (tasks). Note that each core has only
	 *            one task associated to it
	 */
	public SimulatedAnnealingMapper(int gTileNum, int gProcNum) {
		this(gTileNum, gProcNum, false, LegalTurnSet.WEST_FIRST, 1.056f, 2.831f);
	}

	/**
	 * Constructor
	 * 
	 * @param gTileNum
	 *            the size of the 2D mesh (gTileNum * gTileNum)
	 * @param gProcNum
	 *            the number of processes (tasks). Note that each core has only
	 *            one task associated to it
	 * @param buildRoutingTable
	 *            whether or not to build routing table too
	 * @param legalTurnSet
	 *            what {@link LegalTurnSet} the SA algorithm should use (this is
	 *            useful only when the routing table is built)
	 * @param bufReadEBit
	 *            energy consumption per bit read
	 * @param bufWriteEBit
	 *            energy consumption per bit write
	 */
	public SimulatedAnnealingMapper(int gTileNum, int gProcNum,
			boolean buildRoutingTable, LegalTurnSet legalTurnSet,
			float bufReadEBit, float bufWriteEBit) {
		this.gTileNum = gTileNum;
		this.gEdgeSize = (int) Math.sqrt(gTileNum);
		this.gProcNum = gProcNum;
		// we have 2gEdgeSize(gEdgeSize - 1) bidirectional links =>
		// 4gEdgeSize(gEdgeSize - 1) unidirectional links
		this.gLinkNum = 2 * (gEdgeSize - 1) * gEdgeSize * 2;
		this.buildRoutingTable = buildRoutingTable;
		this.legalTurnSet = legalTurnSet;
		this.bufReadEBit = bufReadEBit;
		this.bufWriteEBit = bufWriteEBit;

		gTile = new Tile[gTileNum];

		gProcess = new Process[gProcNum];

		gLink = new Link[gLinkNum];
	}

	public void initializeCores() {
		for (int i = 0; i < gProcess.length; i++) {
			gProcess[i] = new Process(i, -1);
			gProcess[i].setFromCommunication(new int[gTileNum]);
			gProcess[i].setToCommunication(new int[gTileNum]);
			gProcess[i].setFromBandwidthRequirement(new int[gTileNum]);
			gProcess[i].setToBandwidthRequirement(new int[gTileNum]);
		}
	}

	public void initializeNocTopology(int bandwidth, float switchEBit,
			float linkEBit) {
		// initialize nodes
		for (int i = 0; i < gTile.length; i++) {
			gTile[i] = new Tile(i, -1, i / gEdgeSize, i % gEdgeSize, switchEBit);
		}
		// initialize links
		for (int i = 0; i < gLink.length; i++) {
			// There are totally 2*(gEdgeSize-1)*gEdgeSize*2 links. The first
			// half links are horizontal
			// the second half links are vertical links.
			int fromTileRow;
			int fromTileColumn;
			int toTileRow;
			int toTileColumn;
			if (i < 2 * (gEdgeSize - 1) * gEdgeSize) {
				fromTileRow = i / (2 * (gEdgeSize - 1));
				toTileRow = i / (2 * (gEdgeSize - 1));
				int localId = i % (2 * (gEdgeSize - 1));
				if (localId < (gEdgeSize - 1)) {
					// from west to east
					fromTileColumn = localId;
					toTileColumn = localId + 1;
				} else {
					// from east to west
					localId = localId - (gEdgeSize - 1);
					fromTileColumn = localId + 1;
					toTileColumn = localId;
				}
			} else {
				int localId = i - 2 * (gEdgeSize - 1) * gEdgeSize;
				fromTileColumn = localId / (2 * (gEdgeSize - 1));
				toTileColumn = localId / (2 * (gEdgeSize - 1));
				localId = localId % (2 * (gEdgeSize - 1));
				if (localId < (gEdgeSize - 1)) {
					// from south to north
					fromTileRow = localId;
					toTileRow = localId + 1;
				} else {
					// from north to south
					localId = localId - (gEdgeSize - 1);
					fromTileRow = localId + 1;
					toTileRow = localId;
				}
			}

			int fromTileId = fromTileRow * gEdgeSize + fromTileColumn;
			int toTileId = toTileRow * gEdgeSize + toTileColumn;

			gLink[i] = new Link(i, bandwidth, fromTileId, toTileId, linkEBit);
			gLink[i].setFromTileRow(fromTileRow);
			gLink[i].setFromTileColumn(fromTileColumn);
			gLink[i].setToTileRow(toTileRow);
			gLink[i].setToTileColumn(toTileColumn);
		}
		// attach the links to the NoC nodes
		for (int i = 0; i < gTileNum; i++) {
			for (int j = 0; j < gLink.length; j++) {
				if (gLink[j].getFromTileRow() == gTile[i].getRow()
						&& gLink[j].getFromTileColumn() == gTile[i].getColumn()) {
					gTile[i].addOutLink(gLink[j].getLinkId());
				}
				if (gLink[j].getToTileRow() == gTile[i].getRow()
						&& gLink[j].getToTileColumn() == gTile[i].getColumn()) {
					gTile[i].addInLink(gLink[j].getLinkId());
				}
			}
			assert gTile[i].getInLinkList().size() > 0;
			assert gTile[i].getOutLinkList().size() > 0;
		}
		// for each router generate a routing table provided by the XY routing
		// protocol
		for (int i = 0; i < gTileNum; i++) {
			gTile[i].generateXYRoutingTable(gTileNum, gEdgeSize, gLink);
		}

		generateLinkUsageList();
	}

	private void generateLinkUsageList() {
		if (this.buildRoutingTable == true) {
			linkUsageList = null;
		} else {
			// Allocate the space for the link usage table
			int[][][] linkUsageMatrix = new int[gTileNum][gTileNum][gLinkNum];

			// Setting up the link usage matrix
			for (int srcId = 0; srcId < gTileNum; srcId++) {
				for (int dstId = 0; dstId < gTileNum; dstId++) {
					if (srcId == dstId) {
						continue;
					}
					Tile currentTile = gTile[srcId];
					while (currentTile.getTileId() != dstId) {
						int linkId = currentTile.routeToLink(srcId, dstId);
						Link link = gLink[linkId];
						linkUsageMatrix[srcId][dstId][linkId] = 1;
						currentTile = gTile[link.getToTileId()];
					}
				}
			}

			// Now build the link usage list
			linkUsageList = new ArrayList[gTileNum][gTileNum];
			for (int src = 0; src < gTileNum; src++) {
				for (int dst = 0; dst < gTileNum; dst++) {
					linkUsageList[src][dst] = new ArrayList<Integer>();
					if (src == dst) {
						continue;
					}
					for (int linkId = 0; linkId < gLinkNum; linkId++) {
						if (linkUsageMatrix[src][dst][linkId] == 1) {
							linkUsageList[src][dst].add(linkId);
						}
					}
				}
			}

			assert this.linkUsageList != null;
			assert linkUsageList.length == gTileNum;
			for (int i = 0; i < linkUsageList.length; i++) {
				assert linkUsageList[i].length == gTileNum;
			}
		}
	}

	private void mapCoresToNocNodesRandomly() {
		Random rand = new Random();
		for (int i = 0; i < gTileNum; i++) {
			int k = Math.abs(rand.nextInt()) % gTileNum;
			while (gTile[k].getProcId() != -1) {
				k = Math.abs(rand.nextInt()) % gTileNum;
			}
			gProcess[i].setTileId(k);
			gTile[k].setProcId(i);
		}

		// // this maps the cores like NoCMap does
		// int[] coreMap = new int[] { 11, 13, 10, 8, 12, 0, 9, 1, 2, 4, 14, 15,
		// 5, 3, 7, 6 };
		// for (int i = 0; i < gProcNum; i++) {
		// gProcess[i].setTileId(coreMap[i]);
		// gTile[coreMap[i]].setProcId(i);
		// }
	}

	private void printCurrentMapping() {
		System.out.println();
		for (int i = 0; i < gProcNum; i++) {
			System.out.println("Core " + gProcess[i].getProcId()
					+ " is mapped to NoC node " + gProcess[i].getTileId());
		}
		System.out.println();
	}

	// ways to gen Random Vars with specific distributions
	/**
	 * Simple random number generator based on the linear-congruential method
	 * using parameters from example D, p 40, Knuth Vol 2.
	 * 
	 * @return a real number uniformly distributed on [0,1]. This version has
	 *         the advantage that it should behave the same on different
	 *         machines, since the generator and starting point are explicitly
	 *         specified.
	 */
	private double uniformRandomVariable() {
		// one small problem: the sequence we use can produce integers larger
		// than the word size used, i.e. they can wrap around negative. We wimp
		// out on this matter and just make them positive again.

		final int A = 147453245;
		final int C = 226908347;
		final int M = 1073741824;

		seed = ((A * seed) + C) % M;
		if (seed < 0) {
			seed = -seed;
		}
		double u = (((double) seed) / ((double) M));
//		System.out.println("uniformRandomVariable() = " + u);
		return u;
	}

	/**
	 * @return a random INTEGER in [imin, imax]
	 */
	private long uniformIntegerRandomVariable(long imin, long imax) {
		double u;
		int m;

		u = uniformRandomVariable();
		m = (int) imin + ((int) Math.floor((double) (imax + 1 - imin) * u));
//		System.out.println("uniformIntegerRandomVariable(" + imin + ", " + imax
//				+ ") = " + m);
		return m;
	}

	/**
	 * Initialize the random number stream
	 **/
	private void initRand(int seed) {
		this.seed = seed;
	}

	/**
	 * the usual metropolis accept criterion
	 * 
	 * @param deltac
	 *            the cost (energy) variation
	 * @param temperature
	 *            the temperature
	 * 
	 * @return <tt>true</tt> for accept, <tt>false</tt>, otherwise
	 */
	private boolean accept(double deltac, double temperature) {
		double pa = -1; // probability of acceptance
		boolean accept = false;
		double r = -1;
		// annealing accept criterion
		if (MathUtils.approximatelyEqual((float) deltac, 0)) {
			// accept it, but record the number of zero cost acceptance
			zeroCostAcceptance++;
		}

		if (MathUtils.definitelyLessThan((float) deltac, 0)
				|| MathUtils.approximatelyEqual((float) deltac, 0)) {
			accept = true;
		} else {
			pa = Math.exp((double) (-deltac) / temperature);
			r = uniformRandomVariable();
			if (MathUtils.definitelyLessThan((float) r, (float)pa)
					|| MathUtils.approximatelyEqual((float) r, (float)pa)) {
				accept = true;
			} else {
				accept = false;
			}
		}
//		System.out.println("deltac " + deltac + " temp " + temperature + " r "
//				+ r + " pa " + pa + " accept " + accept);
		return accept;
	}

	/**
	 * this does the actual evolution of the placement by annealing at a fixed
	 * temperature <tt>t</tt>.
	 */
	private double annealAtTemperature(double t) {
		int acceptCount = 0;
		double totalDeltaCost = 0;

		int unit = attempts / 10;

		// clear the zeroCostAcceptance
		zeroCostAcceptance = 0;

		// this is the main loop doing moves. We do 'attempts' moves in all,
		// then quit at this temperature

		// System.out.println("attempts = " + attempts);
		for (int m = 1; m < attempts; m++) {
			int[] tiles = makeRandomSwap();
			int tile1 = tiles[0];
			int tile2 = tiles[1];
			double newCost = calculateTotalCost();
			double deltaCost = newCost - currentCost;
//			System.out.println("deltaCost " + deltaCost + " newCost " + newCost
//					+ " currentCost " + currentCost);
	        double deltac = deltaCost / currentCost;
			// Note that we use machine epsilon to perform the following
			// comparison between the float numbers
	        if (MathUtils.approximatelyEqual((float)deltac, 0)) {
	            deltac = 0;
	        } else {
	            deltac = deltac * 100;
	        }
			if (accept(deltac, t)) {
//				System.out.println("Accepting...");
				acceptCount++;
				totalDeltaCost += deltaCost;
				currentCost = newCost;
			} else {
//				System.out.println("Rolling back tiles " + tile1 + " and " + tile2);
				swapProcesses(tile1, tile2); // roll back
			}
			if (m % unit == 0) {
				// This is just to print out the process of the algorithm
				System.err.print("#");
				// System.out.println("Current cost = " + currentCost);
				// System.out.println("Delta cost = " + deltaCost);
			}
		}
		System.out.println();
		acceptRatio = ((double) acceptCount) / attempts;

		if (zeroCostAcceptance == acceptCount) {
			zeroTempCnt++;
		}
		else {
			zeroTempCnt = 0;
		}

		if (zeroTempCnt == 10) {
			needStop = true;
		}

		return totalDeltaCost;
	}

	private void anneal() {
		double cost3, cost2;
		boolean done;
		double tol3, tol2, temp;
		double deltaCost;

		if (!buildRoutingTable) {
			linkBandwidthUsage = new int[gLinkNum];
		} else {
			synLinkBandwithUsage = new int[gEdgeSize][gEdgeSize][4];
			saRoutingTable = new int[gEdgeSize][gEdgeSize][gTileNum][gTileNum];
			for (int i = 0; i < saRoutingTable.length; i++) {
				for (int j = 0; j < saRoutingTable[i].length; j++) {
					for (int k = 0; k < saRoutingTable[i][j].length; k++) {
						for (int l = 0; l < saRoutingTable[i][j][k].length; l++) {
							saRoutingTable[i][j][k][l] = -2;
						}
					}
				}
			}
		}

		// set up the global control parameters for this annealing run
		int tempCount = 0;
		cost3 = 999999999;
		cost2 = 999999999;
		currentCost = cost2;

		attempts = gTileNum * gTileNum * 100;
		// attempts = gTileNum * 10;

		initRand(1234567);

		// Determin initial temperature by accepting all moves and
		// calculate variance.
		/*
		 * compute initial temperature anneal_at_temp(10000.0, costcurrent,
		 * &acceptratio, 1); temp = 20.0 * VAR; init_anneal();
		 */

		temp = 100;

		/* here is the temperature cooling loop of the annealer */
		done = false;
		do {
			needStop = false;

			System.out.println("Round " + tempCount + ":");
			System.out.println("Current Annealing temperature " + temp);

			deltaCost = annealAtTemperature(temp);
			
//			System.exit(-1);

			System.out.println("total delta cost " + deltaCost);
			System.out.println("Current cost " + currentCost);
			System.out.println("Accept ratio " + acceptRatio);
			
//			printCurrentMapping();

			// OK, if we got here the cost function is working fine. We can
			// now look at whether we are frozen, or whether we should cool some
			// more. We basically just look at the last 2 temperatures, and
			// see if the cost is not changing much (that's the TOLERANCE test)
			// and if the we have done enough temperatures (that's the TEMPS
			// test), and if the accept ratio fraction is small enough (that is
			// the MINACCEPT test). If all are satisfied, we quit.

			tol3 = ((double) cost3 - (double) cost2) / (double) cost3;
			if (tol3 < 0) {
				tol3 = -tol3;
			}
			tol2 = ((double) cost2 - (double) currentCost) / (double) cost2;
			if (tol2 < 0) {
				tol2 = -tol2;
			}

			if (MathUtils.definitelyLessThan((float) tol3, TOLERANCE)
					&& MathUtils.definitelyLessThan((float) tol3, TOLERANCE)
					&& tempCount > TEMPS
					&& (MathUtils.definitelyLessThan((float) acceptRatio,
							(float) MINACCEPT) || needStop)) {
				done = true;
			} else {
				// save the relevant info to test for frozen after the NEXT
				// temperature.
				cost3 = cost2;
				cost2 = currentCost;
				temp = 0.9 * temp;
				tempCount++;
			}
		} while (!done);
		if (buildRoutingTable) {
			programRouters();
		}
	}

	/**
	 * Randomly picks two tiles and swaps them
	 * 
	 * @return an array with exactly 2 integers
	 */
	private int[] makeRandomSwap() {
		int tile1 = (int) uniformIntegerRandomVariable(0, gTileNum - 1);
		int tile2 = -1;

		while (true) {
			// select two tiles to swap
			tile2 = (int) uniformIntegerRandomVariable(0, gTileNum - 1);
			if (tile1 != tile2
					&& (gTile[tile1] != null || gTile[tile2] != null)) {
				break;
			}
		}

		// Swap the processes attached to these two tiles
		swapProcesses(tile1, tile2);
		return new int[] { tile1, tile2 };
	}

	/**
	 * Swaps the processes from tiles with IDs t1 and t2
	 * 
	 * @param t1
	 *            the ID of the first tile
	 * @param t2
	 *            the ID of the second tile
	 */
	private void swapProcesses(int t1, int t2) {
		Tile tile1 = gTile[t1];
		Tile tile2 = gTile[t2];
		assert tile1 != null;
		assert tile2 != null;

		int p1 = tile1.getProcId();
		int p2 = tile2.getProcId();
		
//		System.out.println("Swapping process " + p1 + " of tile " + t1
//				+ " with process " + p2 + " of tile " + t2);
		
		tile1.setProcId(p2);
		tile2.setProcId(p1);
		if (p1 != -1) {
			Process process = gProcess[p1];
			if (process == null) {
				process = new Process(p1, t2);
			} else {
				process.setTileId(t2);
			}
		}
		if (p2 != -1) {
			Process process = gProcess[p2];
			if (process == null) {
				process = new Process(p2, t1);
			} else {
				process.setTileId(t1);
			}
		}
	}

	/**
	 * Calculate the total cost in terms of the sum of the energy consumption
	 * and the penalty of the link overloading
	 * 
	 * @return the total cost
	 */
	private float calculateTotalCost() {
		// the communication energy part
		float energyCost = calculateCommunicationEnergy();
		float overloadCost;
		// now calculate the overloaded BW cost
		if (!buildRoutingTable) {
			overloadCost = calculateOverloadWithFixedRouting();
		} else {
			overloadCost = calculateOverloadWithAdaptiveRouting();
		}
//		System.out.println("energy cost " + energyCost);
//		System.out.println("overload cost " + overloadCost);
//		System.out.println("total cost " + (energyCost + overloadCost));
		return energyCost + overloadCost;
	}

	/**
	 * Computes the overload of the links when no routing is performed
	 * 
	 * @return the overload
	 */
	private float calculateOverloadWithFixedRouting() {
		Arrays.fill(linkBandwidthUsage, 0);
		for (int proc1 = 0; proc1 < gProcNum; proc1++) {
			for (int proc2 = proc1 + 1; proc2 < gProcNum; proc2++) {
				if (gProcess[proc1].getToBandwidthRequirement()[proc2] > 0) {
					int tile1 = gProcess[proc1].getTileId();
					int tile2 = gProcess[proc2].getTileId();
					for (int i = 0; i < linkUsageList[tile1][tile2].size(); i++) {
						int linkId = linkUsageList[tile1][tile2].get(i);
						linkBandwidthUsage[linkId] += gProcess[proc1]
								.getToBandwidthRequirement()[proc2];
					}
				}
				if (gProcess[proc1].getFromBandwidthRequirement()[proc2] > 0) {
					int tile1 = gProcess[proc1].getTileId();
					int tile2 = gProcess[proc2].getTileId();
					for (int i = 0; i < linkUsageList[tile1][tile2].size(); i++) {
						int linkId = linkUsageList[tile2][tile1].get(i);
						linkBandwidthUsage[linkId] += gProcess[proc1]
								.getFromBandwidthRequirement()[proc2];
					}
				}
			}
		}
		float overloadCost = 0;
		for (int i = 0; i < gLinkNum; i++) {
			if (linkBandwidthUsage[i] > gLink[i].getBandwidth()) {
				overloadCost = ((float) linkBandwidthUsage[i])
						/ gLink[i].getBandwidth() - 1.0f;
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
		for (int i = 0; i < gEdgeSize; i++) {
			for (int j = 0; j < gEdgeSize; j++) {
				Arrays.fill(synLinkBandwithUsage[i][j], 0);
			}
		}

		for (int src = 0; src < gProcNum; src++) {
			for (int dst = 0; dst < gProcNum; dst++) {
				int tile1 = gProcess[src].getTileId();
				int tile2 = gProcess[dst].getTileId();
				if (gProcess[src].getToBandwidthRequirement()[dst] > 0) {
					routeTraffic(tile1, tile2,
							gProcess[src].getToBandwidthRequirement()[dst]);
				}
			}
		}

		for (int i = 0; i < gEdgeSize; i++) {
			for (int j = 0; j < gEdgeSize; j++) {
				for (int k = 0; k < 4; k++) {
					if (synLinkBandwithUsage[i][j][k] > gLink[0].getBandwidth()) {
						overloadCost += ((float) synLinkBandwithUsage[i][j][k])
								/ gLink[0].getBandwidth() - 1.0;
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
	 * @param srcTile
	 *            the source tile
	 * @param dstTile
	 *            the destination tile
	 * @param bandwidth
	 *            the bandwidth
	 */
	private void routeTraffic(int srcTile, int dstTile, int bandwidth) {
		boolean commit = true;

		int srcRow = gTile[srcTile].getRow();
		int srcColumn = gTile[srcTile].getColumn();
		int dstRow = gTile[dstTile].getRow();
		int dstColumn = gTile[dstTile].getColumn();

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
									System.err.println("Error. Exiting...");
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
				saRoutingTable[row][col][srcTile][dstTile] = direction;
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
				System.err.println("Error. Unknown direction!");
				break;
			}
		}
	}

	private void programRouters() {
		// clean all the old routing table
		for (int tileId = 0; tileId < gTileNum; tileId++) {
			for (int srcTile = 0; srcTile < gTileNum; srcTile++) {
				for (int dstTile = 0; dstTile < gTileNum; dstTile++) {
					if (tileId == dstTile) {
						gTile[tileId].setRoutingEntry(srcTile, dstTile, -1);
					} else {
						gTile[tileId].setRoutingEntry(srcTile, dstTile, -2);
					}
				}
			}
		}

		for (int row = 0; row < gEdgeSize; row++) {
			for (int col = 0; col < gEdgeSize; col++) {
				int tileId = row * gEdgeSize + col;
				for (int srcTile = 0; srcTile < gTileNum; srcTile++) {
					for (int dstTile = 0; dstTile < gTileNum; dstTile++) {
						int linkId = locateLink(row, col,
								saRoutingTable[row][col][srcTile][dstTile]);
						if (linkId != -1) {
							gTile[tileId].setRoutingEntry(srcTile, dstTile,
									linkId);
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
	private float calculateCommunicationEnergy() {
		float switchEnergy = calculateSwitchEnergy();
		float linkEnergy = calculateLinkEnergy();
		float bufferEnergy = calculateBufferEnergy();
//		System.out.println("switch energy " + switchEnergy);
//		System.out.println("link energy " + linkEnergy);
//		System.out.println("buffer energy " + bufferEnergy);
		return switchEnergy + linkEnergy + bufferEnergy;
	}

	private float calculateSwitchEnergy() {
		float energy = 0;
		for (int src = 0; src < gTileNum; src++) {
			for (int dst = 0; dst < gTileNum; dst++) {
				int srcProc = gTile[src].getProcId();
				int dstProc = gTile[dst].getProcId();
				int commVol = gProcess[srcProc].getToCommunication()[dstProc];
				if (commVol > 0) {
					energy += gTile[src].getCost() * commVol;
					Tile currentTile = gTile[src];
//					 System.out.println("adding " + currentTile.getCost()
//					 + " * " + commVol + " (core " + srcProc
//					 + " to core " + dstProc + ") current tile "
//					 + currentTile.getTileId());
					while (currentTile.getTileId() != dst) {
						int linkId = currentTile.getRoutingEntries()[src][dst];
						currentTile = gTile[gLink[linkId].getToTileId()];
						energy += currentTile.getCost() * commVol;
//						 System.out.println("adding " + currentTile.getCost()
//						 + " * " + commVol + " (core " + srcProc
//						 + " to core " + dstProc + ") current tile "
//						 + currentTile.getTileId() + " link ID " + linkId);
					}
				}
			}
		}
		return energy;
	}

	private float calculateLinkEnergy() {
		float energy = 0;
		for (int src = 0; src < gTileNum; src++) {
			for (int dst = 0; dst < gTileNum; dst++) {
				int srcProc = gTile[src].getProcId();
				int dstProc = gTile[dst].getProcId();
				int commVol = gProcess[srcProc].getToCommunication()[dstProc];
				if (commVol > 0) {
					Tile currentTile = gTile[src];
					while (currentTile.getTileId() != dst) {
						int linkId = currentTile.getRoutingEntries()[src][dst];
						energy += gLink[linkId].getCost() * commVol;
						currentTile = gTile[gLink[linkId].getToTileId()];
					}
				}
			}
		}
		return energy;
	}

	private float calculateBufferEnergy() {
		float energy = 0;
		for (int src = 0; src < gTileNum; src++) {
			for (int dst = 0; dst < gTileNum; dst++) {
				int srcProc = gTile[src].getProcId();
				int dstProc = gTile[dst].getProcId();
				int commVol = gProcess[srcProc].getToCommunication()[dstProc];
				if (commVol > 0) {
					Tile currentTile = gTile[src];
					while (currentTile.getTileId() != dst) {
						int linkId = currentTile.getRoutingEntries()[src][dst];
						energy += (bufReadEBit + bufWriteEBit) * commVol;
						currentTile = gTile[gLink[linkId].getToTileId()];
					}
					energy += bufWriteEBit * commVol;
				}
			}
		}
		return energy;
	}

	/**
	 * find out the link ID. Ff the direction is not set, return -1
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
		for (linkId = 0; linkId < gLinkNum; linkId++) {
			if (gTile[gLink[linkId].getFromTileId()].getRow() == origRow
					&& gTile[gLink[linkId].getFromTileId()].getColumn() == origColumn
					&& gTile[gLink[linkId].getToTileId()].getRow() == row
					&& gTile[gLink[linkId].getToTileId()].getColumn() == column)
				break;
		}
		if (linkId == gLinkNum) {
			System.err.println("Error in locating link");
			System.exit(-1);
		}
		return linkId;
	}

	private boolean verifyBandwidthRequirement() {
		generateLinkUsageList();

		for (int i = 0; i < gLinkNum; i++) {
			gLink[i].setUsedBandwidth(0);
		}

		for (int src = 0; src < gTileNum; src++) {
			for (int dst = 0; dst < gTileNum; dst++) {
	            if (src == dst) {
	                continue;
	            }
	            int srcProc = gTile[src].getProcId();
	            int dstProc = gTile[dst].getProcId();
	            int commLoad = gProcess[srcProc].getToBandwidthRequirement()[dstProc];
	            if (commLoad == 0) {
	                continue;
	            }
	            Tile currentTile = gTile[src];
	            while (currentTile.getTileId() != dst) {
	                int linkId = currentTile.routeToLink(src, dst);
	                Link link = gLink[linkId];
	                currentTile = gTile[link.getToTileId()];
	                gLink[linkId].setUsedBandwidth(gLink[linkId].getUsedBandwidth() + commLoad);
	            }
	        }
	    }
	    //check for the overloaded links
	    int violations = 0;
		for (int i = 0; i < gLinkNum; i++) {
	        if (gLink[i].getUsedBandwidth()> gLink[i].getBandwidth()) {
	        	System.out.println("Link " + i + " is overloaded: " + gLink[i].getUsedBandwidth() + " > "
	                 + gLink[i].getBandwidth());
	            violations ++;
	        }
	    }
		return violations == 0;
	}
	
	public void analyzeIt() {
	    System.out.print("Verify the communication load of each link...");
	    if (verifyBandwidthRequirement()) {
	    	System.out.println("Succeed.");
	    }
	    else {
	    	System.out.println("Fail.");
	    }
	    System.out.println("Energy consumption estimation ");
	    System.out.println("(note that this is not exact numbers, but serve as a relative energy indication) ");
	    System.out.println("Energy consumed in link is " + calculateLinkEnergy());
	    System.out.println("Energy consumed in switch is " + calculateSwitchEnergy());
	    System.out.println("Energy consumed in buffer is " + calculateBufferEnergy());
	    System.out.println("Total communication energy consumption is " + calculateCommunicationEnergy());
	}
	
	@Override
	public String map() throws TooFewNocNodesException {
		if (gTileNum < gProcNum) {
			throw new TooFewNocNodesException(gProcNum, gTileNum);
		}

		mapCoresToNocNodesRandomly();

		printCurrentMapping();

		anneal();

		return null;
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
					e.printStackTrace();
				}
				// System.err.print("ID = " + id);
			}

			if (line.contains("packet_to_destination_rate")) {
				String substring = line.substring(
						"packet_to_destination_rate".length() + 1).trim();
				int dstId = -1;
				try {
					dstId = Integer.valueOf(substring.substring(0,
							substring.indexOf("\t")));
					// System.err.print(" dst ID = " + dstId);
				} catch (NumberFormatException e) {
					e.printStackTrace();
				}
				double rate = 0;
				try {
					rate = Double.valueOf(substring.substring(substring
							.indexOf("\t") + 1));
					// System.err.print(" rate = " + rate);
				} catch (NumberFormatException e) {
					e.printStackTrace();
				}

				if (rate > 1) {
					System.err.println("Invalid rate!");
					System.exit(0);
				}
				gProcess[id].getToCommunication()[dstId] = (int) (rate * 1000000);
				gProcess[id].getToBandwidthRequirement()[dstId] = (int) (rate * 3 * linkBandwidth);
				gProcess[dstId].getFromCommunication()[id] = (int) (rate * 1000000);
				gProcess[dstId].getFromBandwidthRequirement()[id] = (int) (rate * 3 * linkBandwidth);
				// System.err.println();
			}
		}

		br.close();
	}

	public static void main(String[] args) throws TooFewNocNodesException,
			IOException {
		if (args == null || args.length < 1) {
			System.err.println("usage: SimulatedAnnealingMapper {routing}");
			System.err.println("(where routing may be true or false; any other value means false)");
		} else {
			// from the initial random mapping, I think tiles must equal cores (it
			// is not enough to have cores <= tiles)
			int tiles = 16;
			int cores = 16;
			int linkBandwidth = 1000000;
			float switchEBit = 0.284f;
			float linkEBit = 0.449f;
			float bufReadEBit = 1.056f;
			float burWriteEBit = 2.831f;
	
			SimulatedAnnealingMapper saMapper;
			if ("true".equals(args[0])) {
				// SA with routing
				saMapper = new SimulatedAnnealingMapper(tiles, cores, true,
						LegalTurnSet.ODD_EVEN, bufReadEBit, burWriteEBit);
			} else {
				// SA without routing
				saMapper = new SimulatedAnnealingMapper(tiles, cores);
			}
			saMapper.initializeCores();
			saMapper.initializeNocTopology(linkBandwidth, switchEBit, linkEBit);
			
	
			saMapper.parseTrafficConfig(
					"telecom-mocsyn-16tile-selectedpe.traffic.config",
					linkBandwidth);
	
			saMapper.map();
	
			saMapper.printCurrentMapping();
			
			saMapper.analyzeIt();
		}
	}
}
