package ro.ulbsibiu.acaps.mapper.ga.ea;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import javax.xml.bind.JAXBException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.Logger;

import ro.ulbsibiu.acaps.ctg.xml.apcg.ApcgType;
import ro.ulbsibiu.acaps.ctg.xml.ctg.CtgType;
import ro.ulbsibiu.acaps.ctg.xml.mapping.MapType;
import ro.ulbsibiu.acaps.mapper.BandwidthConstrainedEnergyAndPerformanceAwareMapper;
import ro.ulbsibiu.acaps.mapper.MapperDatabase;
import ro.ulbsibiu.acaps.mapper.TooFewNocNodesException;
import ro.ulbsibiu.acaps.mapper.ga.GenticAlgorithmInputException;
import ro.ulbsibiu.acaps.mapper.ga.Individual;
import ro.ulbsibiu.acaps.mapper.util.MapperInputProcessor;

public class EnergyAwareGeneticAlgorithmMapper extends BandwidthConstrainedEnergyAndPerformanceAwareMapper {

	/**
	 * Logger for this class
	 */
	private static final Logger logger = Logger
			.getLogger(EnergyAwareGeneticAlgorithmMapper.class);

	private static final String MAPPER_ID = "eaga";

	private static int populationSize;

	private static int crossoverPr;

	private static int mutationPr;

	private static int noOfGenerationToRun;

	private static int percentageOfOldPopuToNewPopu = 20;

	private static int tournamentSize;

	private List<Individual> population;

	private List<Individual> newPopulation;

	private int[] currentChild1;
	
	private int[] currentChild2;

	private int currentNoOfGeneration;
	
	/** the seed for the random number generator of the initial population */
	private Long seed;

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
	 * @param seed
	 *            the seed for the random number generator of the initial
	 *            population
	 */
	public EnergyAwareGeneticAlgorithmMapper(String benchmarkName, String ctgId,
			String apcgId, String topologyName, String topologySize,
			File topologyDir, int coresNumber, double linkBandwidth,
			float switchEBit, float linkEBit, Long seed) throws JAXBException {
		this(benchmarkName, ctgId, apcgId, topologyName, topologySize,
				topologyDir, coresNumber, linkBandwidth, false,
				LegalTurnSet.WEST_FIRST, 1.056f, 2.831f, switchEBit, linkEBit, seed);
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
	 * @param seed
	 *            the seed for the random number generator of the initial
	 *            population (can be null)
	 * @throws JAXBException
	 */
	public EnergyAwareGeneticAlgorithmMapper(String benchmarkName,
			String ctgId, String apcgId, String topologyName,
			String topologySize, File topologyDir, int coresNumber,
			double linkBandwidth, boolean buildRoutingTable,
			LegalTurnSet legalTurnSet, float bufReadEBit, float bufWriteEBit,
			float switchEBit, float linkEBit, Long seed) throws JAXBException {
		
		super(benchmarkName, ctgId, apcgId, topologyName, topologySize,
				topologyDir, coresNumber, linkBandwidth, buildRoutingTable,
				legalTurnSet, bufReadEBit, bufWriteEBit, switchEBit, linkEBit);

		this.currentNoOfGeneration = 0;
		this.population = new ArrayList<Individual>(populationSize);
		this.newPopulation = new ArrayList<Individual>(populationSize);
		this.currentChild1 = new int[nodes.length];
		this.currentChild2 = new int[nodes.length];
		this.seed = seed;
	}

	/**
	 * this function is used for initialization of the population
	 */

	private void doInitPopulation() {
		logger.info("Randomly creating initial population");
		Random rand;
		if (seed == null) {
			rand = new Random();
		} else {
			rand = new Random(seed);
		}

		/* initialized in this way so that no number will be repeated */
		for (int i = 0; i < populationSize; i++) {
			int[] tempIndividual = new int[nodes.length];
			Arrays.fill(tempIndividual, -1);

			for (int j = 0; j < cores.length; j++) {
				int k = Math.abs(rand.nextInt()) % nodes.length;
				while (tempIndividual[k] != -1) {
					k = Math.abs(rand.nextInt()) % nodes.length;
				}
				tempIndividual[k] = j;
			}
				
			// calculate the fitness here
			double fitnessOfIndividual = fitnessCalculation(tempIndividual);
			population.add(new Individual(tempIndividual, fitnessOfIndividual));
		}
	}

	/**
	 * do Position based crossover between two parents generate two new children
	 * 
	 * @param pr1
	 *            position of the parent # 1 in the population
	 * @param pr2
	 *            position of the parent # 2 in the population
	 */
	private void doPositionBasedCrossOver(int pr1, int pr2) {
		if (logger.isDebugEnabled()) {
			logger.debug("Position based crossover using individuals " + pr1 + " and " + pr2);
		}
		
		// two parents that is used for crossover
		int parent1[] = new int[nodes.length];
		int parent2[] = new int[nodes.length];

		// two children that is created after crossover
		int child1[] = new int[nodes.length];
		int child2[] = new int[nodes.length];

		// copy parents from population
		parent1 = Arrays.copyOf(population.get(pr1).getGenes(),
				population.get(pr1).getGenes().length);
		parent2 = Arrays.copyOf(population.get(pr2).getGenes(),
				population.get(pr2).getGenes().length);

		Random rand;
		if (seed == null) {
			rand = new Random();
		} else {
			rand = new Random(seed);
		};

		if (rand.nextInt(100) <= crossoverPr) {
			for (int i = 0; i < child1.length; i++) {
				child1[i] = child2[i] = -1;
			}

			// 25 percent of the gene is used as positions
			int numberOfPositions = (int) ((nodes.length / 100.0) * 25);

			int[] setOfPositions = new int[numberOfPositions];
			for (int i = 0; i < setOfPositions.length; i++) {
				setOfPositions[i] = -1;
			}

			for (int i = 0; i < setOfPositions.length; i++) {
				int number;
				// be sure that position is not repeated
				boolean track;
				do {
					track = false;
					number = rand.nextInt(nodes.length);
					for (int k = 0; k < setOfPositions.length; k++)
						if (number == setOfPositions[k]) {
							track = true;
							break;
						}

				} while (track);
				setOfPositions[i] = number;
			}

			int[] tempParent = new int[nodes.length];

			tempParent = Arrays.copyOf(parent2, parent2.length);

			/*
			 * copy the content of the specific position(setOfPosition) from
			 * parent1 to child1. Also set -1 in position of parent2
			 * (tempParent) where the content of the parent1 of the specific
			 * position (setOfPosition) is matched
			 */
			for (int i = 0; i < setOfPositions.length; i++) {
				child1[setOfPositions[i]] = parent1[setOfPositions[i]];
				int j;
				for (j = 0; j < tempParent.length; j++) {
					if (tempParent[j] == parent1[setOfPositions[i]]) {
						tempParent[j] = -1;
						break;
					}
				}
			}
			/*
			 * starting from left to right of parent2(tempParent), copy content
			 * from parent2 to the rest of the position of child1 by skipping -1
			 */
			for (int i = 0; i < tempParent.length; i++) {

				if (tempParent[i] == -1)
					continue;
				else {
					for (int j = 0; j < child1.length; j++) {
						if (child1[j] != -1)
							continue;
						else {
							child1[j] = tempParent[i];
							break;
						}
					}
				}

			}
			// child1 finished

			// child2 start

			for (int i = 0; i < setOfPositions.length; i++)
				setOfPositions[i] = -1;

			for (int i = 0; i < setOfPositions.length; i++) {
				int number;
				// be sure that position is not repeated
				boolean track;
				do {
					track = false;
					number = rand.nextInt(nodes.length);
					for (int k = 0; k < setOfPositions.length; k++)
						if (number == setOfPositions[k]) {
							track = true;
							break;
						}

				} while (track);

				setOfPositions[i] = number;

			}

			tempParent = Arrays.copyOf(parent1, parent1.length);

			for (int i = 0; i < setOfPositions.length; i++) {
				child2[setOfPositions[i]] = parent2[setOfPositions[i]];
				int j;
				for (j = 0; j < tempParent.length; j++) {
					if (tempParent[j] == parent2[setOfPositions[i]]) {
						tempParent[j] = -1;
						break;
					}
				}

			}

			for (int i = 0; i < tempParent.length; i++) {
				if (tempParent[i] == -1)
					continue;
				else {
					for (int j = 0; j < child2.length; j++) {
						if (child2[j] != -1)
							continue;
						else {
							child2[j] = tempParent[i];
							break;
						}
					}
				}
			}
			currentChild1 = Arrays.copyOf(child1, child1.length);
			currentChild2 = Arrays.copyOf(child2, child2.length);
		} else {
			// the random number is greater that crossoverPr
			currentChild1 = Arrays.copyOf(parent1, parent1.length);
			currentChild2 = Arrays.copyOf(parent2, parent2.length);
		}
		
		if (logger.isDebugEnabled()) {
			logger.debug("First child obtained through crossover is " + Arrays.toString(currentChild1));
			logger.debug("Second child obtained through crossover is " + Arrays.toString(currentChild2));
		}

	}

	/**
	 * do Cut and crossfill crossover between two parent
	 * 
	 * @param pr1
	 *            position of the parent # 1 in the population
	 * @param pr2
	 *            position of the parent # 2 in the population
	 */
	private void doCutAndCrossfillCrossoverV1(int pr1, int pr2) {
		Random rand;
		if (seed == null) {
			rand = new Random();
		} else {
			rand = new Random(seed);
		}

		// two parents that is used for crossover
		int parent1[] = new int[nodes.length];
		int parent2[] = new int[nodes.length];

		// two children that is created after crossover
		int child1[] = new int[nodes.length];
		int child2[] = new int[nodes.length];

		// copy parents from population
		parent1 = Arrays.copyOf(population.get(pr1).getGenes(),
				population.get(pr1).getGenes().length);
		parent2 = Arrays.copyOf(population.get(pr2).getGenes(),
				population.get(pr2).getGenes().length);

		if (rand.nextInt(100) <= crossoverPr) {

			int cr_point;
			// find a crossover point within 1 to noOfNodes-1
			do {
				cr_point = rand.nextInt(nodes.length - 1);
			} while (cr_point == 0);

			// copy first part from parent
			for (int i = 0; i < cr_point; i++) {
				child1[i] = parent1[i];
				child2[i] = parent2[i];
			}

			// copy next part in the same order skipping values already in the
			// child
			int in_ch1, in_ch2;
			in_ch1 = in_ch2 = cr_point;
			boolean track_ch1, track_ch2;
			for (int i = cr_point; i < nodes.length; i++) {
				// for parent1
				track_ch2 = true;
				int temp = parent1[i];
				for (int j = 0; j < cr_point; j++) {
					if (temp == child2[j]) {
						track_ch2 = false;
						break;

					}
				}
				if (track_ch2) {
					child2[in_ch2++] = temp;
				}

				// for parent2
				track_ch1 = true;
				int temp2 = parent2[i];
				for (int j = 0; j < cr_point; j++) {
					if (temp2 == child1[j]) {
						track_ch1 = false;
						break;
					}
				}
				if (track_ch1) {
					child1[in_ch1++] = temp2;

				}
			}

			// copy last part from parent to child
			// for child 1 -> copy from parent2
			boolean track = true;
			for (int i = in_ch1; i < nodes.length; i++) {
				for (int j = 0; j < cr_point; j++) {
					track = true;
					int temp = parent2[j];
					for (int z = 0; z < i; z++) {
						if (temp == child1[z]) {
							track = false;
							break;
						}
					}
					if (track) {
						child1[i] = temp;
						break;
					}
				}
			}

			// for child2-> copy from parent 1
			track = true;
			for (int i = in_ch2; i < nodes.length; i++) {
				for (int j = 0; j < cr_point; j++) {
					track = true;
					int temp = parent1[j];
					for (int z = 0; z < i; z++) {
						if (temp == child2[z]) {
							track = false;
							break;
						}
					}
					if (track) {
						child2[i] = temp;
						break;
					}
				}
			}
			currentChild1 = child1;
			currentChild2 = child2;
		}
		// the random number is greater that crossoverPr
		else {
			currentChild1 = parent1;
			currentChild2 = parent2;

		}

	}

	private void doCutAndCrossfillCrossoverV2(int pr1, int pr2) {
		Random rand;
		if (seed == null) {
			rand = new Random();
		} else {
			rand = new Random(seed);
		}

		// two parents that is used for crossover
		int parent1[] = new int[nodes.length];
		int parent2[] = new int[nodes.length];

		// two children that is created after crossover
		int child1[] = new int[nodes.length];
		int child2[] = new int[nodes.length];

		// copy parents from population
		parent1 = Arrays.copyOf(population.get(pr1).getGenes(),
				population.get(pr1).getGenes().length);
		parent2 = Arrays.copyOf(population.get(pr2).getGenes(),
				population.get(pr2).getGenes().length);

		if (rand.nextInt(100) <= crossoverPr) {

			int crPoint;
			// find a crossover point within 1 to noOfNodes-1
			do {
				crPoint = rand.nextInt(nodes.length - 1);
			} while (crPoint == 0);

			// copy first part from parent
			for (int i = 0; i < crPoint; i++) {
				child1[i] = parent1[i];
				child2[i] = parent2[i];
			}

			// copy next part in the same order skipping values already in the
			// child
			int indexChild1 = crPoint;
			boolean trackChild1;
			for (int i = 0; i < nodes.length; i++) {
				// for parent1
				trackChild1 = true;
				int temp = parent2[i];
				for (int j = 0; j < crPoint; j++) {
					if (temp == child1[j]) {
						trackChild1 = false;
						break;

					}
				}
				if (trackChild1) {
					child1[indexChild1++] = temp;
				}
			}

			// for child2
			int indexChild2 = crPoint;
			boolean trackChild2;
			for (int i = 0; i < nodes.length; i++) {
				// for parent1
				trackChild2 = true;
				int temp = parent1[i];
				for (int j = 0; j < crPoint; j++) {
					if (temp == child2[j]) {
						trackChild2 = false;
						break;

					}
				}
				if (trackChild2) {
					child2[indexChild2++] = temp;
				}
			}
			currentChild1 = child1;
			currentChild2 = child2;

		} else {
			currentChild1 = parent1;
			currentChild2 = parent2;
		}
	}

	/**
	 * this function do mutation of the two current children -> currentChild1
	 * and currentChild2. Here we use only swapping two randomly selected number
	 * in the array
	 **/
	private void doMutationv1() {
		if (logger.isDebugEnabled()) {
			logger.debug("Applying swapping based mutation for the two individuals obtains through the previous crossover");
		}
		
		Random rand;
		if (seed == null) {
			rand = new Random();
		} else {
			rand = new Random(seed);
		}

		int pos1Forchild1, pos2Forchild1, pos1Forchild2, pos2Forchild2, temp;

		pos1Forchild1 = rand.nextInt(nodes.length);
		pos2Forchild1 = rand.nextInt(nodes.length);

		pos1Forchild2 = rand.nextInt(nodes.length);
		pos2Forchild2 = rand.nextInt(nodes.length);

		if (rand.nextInt(100) <= mutationPr) {
			temp = currentChild1[pos1Forchild1];
			currentChild1[pos1Forchild1] = currentChild1[pos2Forchild1];
			currentChild1[pos2Forchild1] = temp;
			if (logger.isDebugEnabled()) {
				logger.debug("First individual mutated to " + Arrays.toString(currentChild1));
			}
		}
		if (rand.nextInt(100) <= mutationPr) {
			temp = currentChild2[pos1Forchild2];
			currentChild2[pos1Forchild2] = currentChild2[pos2Forchild2];
			currentChild2[pos2Forchild2] = temp;
			if (logger.isDebugEnabled()) {
				logger.debug("Second individual mutated to " + Arrays.toString(currentChild2));
			}
		}
	}

	/**
	 * this method is used to swap two cores but within two one must be used in
	 * current apcg
	 **/
	/*
	 * private void doMutationv2() {
	 * 
	 * Random rm = new Random(); int pos1, pos2, temp;
	 * 
	 * if(Math.abs(rm.nextInt()) % 100 <= mutationPr){
	 * 
	 * boolean track = true; do { pos1 = Math.abs(rm.nextInt()) % noOfNodes;
	 * //we try to find a position where we have a core that is in apcg for(int
	 * i = 0; i < this.currentApcg.getCore().size(); i++)
	 * if(this.currentApcg.getCore
	 * ().get(i).getUid().equals(""+currentChild1[pos1])) { track = false;
	 * break; } }while(track); pos2 = Math.abs(rm.nextInt()) % noOfNodes; temp =
	 * currentChild1[pos1]; currentChild1[pos1] = currentChild1[pos2];
	 * currentChild1[pos2] = temp;
	 * 
	 * }
	 * 
	 * if(Math.abs(rm.nextInt()) % 100 <= mutationPr) { boolean track = true; do
	 * { pos1 = Math.abs(rm.nextInt()) % noOfNodes; //we try to find a position
	 * where we have a core that is in apcg for(int i = 0; i <
	 * this.currentApcg.getCore().size(); i++)
	 * if(this.currentApcg.getCore().get(
	 * i).getUid().equals(""+currentChild2[pos1])) { track = false; break; }
	 * 
	 * }while(track); pos2 = Math.abs(rm.nextInt()) % noOfNodes; temp =
	 * currentChild2[pos1]; currentChild2[pos1] = currentChild2[pos2];
	 * currentChild2[pos2]=temp;
	 * 
	 * }
	 * 
	 * 
	 * 
	 * }
	 */

	/**
	 * @param individual
	 *            the individual that need to calculate fitness
	 * 
	 * @return the fitness of the individual
	 */
	private double fitnessCalculation(int[] individual) {
		logger.assertLog(individual != null, "Attempting to compute fitness for a NULL individual!");
		logger.assertLog(individual.length == nodes.length, "The individual doesn't contains " + nodes.length + " genes!");
		
		double fitness = 0;
		
		for (int i = 0; i < individual.length; i++) {
			if (individual[i] != -1) {
				cores[individual[i]].setNodeId(i);
			}
			nodes[i].setCore(Integer.toString(individual[i]));
		}
		
		fitness = calculateTotalCost();
		if (logger.isDebugEnabled()) {
			logger.debug("Computed a fitness of " + fitness + " for individual " + Arrays.toString(individual));
		}
		
		return fitness;
	}

	/**
	 * used for inserting two current children (currentChild1, currentChild2)
	 * into mating pool (newPopulation array).
	 * 
	 * This function is called each time after crossover and mutation have done.
	 **/

	private void insertCurrentChildrenIntoNewPopulation() {
		if (logger.isDebugEnabled()) {
			logger.debug("Inserting the two children into the new population");
		}
		newPopulation.add(new Individual(currentChild1, fitnessCalculation(currentChild1)));
		newPopulation.add(new Individual(currentChild2, fitnessCalculation(currentChild2)));
	}

	/**
	 * This function find a parent by using Tournament selection algorithm
	 * 
	 * @param tournamentSize
	 *            Specify the size of the tournament
	 * 
	 * @return position of the parent in the array
	 */

	private int tournamentSelection(int tournamentSize) {
		if (tournamentSize <= 0) {
			logger.fatal("The tournament size must be a positive number! Exiting...");
			System.exit(-1);
		}
		
		if (logger.isDebugEnabled()) {
			logger.debug("(Deterministic) tournament selection");
		}
		
		Random rand;
		if (seed == null) {
			rand = new Random();
		} else {
			rand = new Random(seed);
		}
		// save the position of randomly selected potential parents
		int posParent[] = new int[tournamentSize];

		for (int i = 0; i < posParent.length; i++)
			posParent[i] = -1;

		for (int i = 0; i < tournamentSize; i++) {
			boolean track;
			int position;
			// be sure that position is not repeated
			do {
				track = false;
				position = rand.nextInt(populationSize);
				for (int k = 0; k < posParent.length; k++)
					if (position == posParent[k]) {
						track = true;
						break;
					}

			} while (track);
			posParent[i] = position;
		}

		double f = Double.MIN_VALUE, fitness;
		int posOfParent = -1;

		for (int i = 0; i < tournamentSize; i++) {
			fitness = population.get(i).getFitness();
			if (fitness > f) {
				f = fitness;
				posOfParent = posParent[i];
			}
		}
		
		if (logger.isDebugEnabled()) {
			logger.debug("Selected parent " + posOfParent);
		}
		
		return posOfParent;
	}

	private void createPopulationElitismV2() {
		if (logger.isDebugEnabled()) {
			logger.debug("Applying ellitism to the population");
		}
		
		/**
		 * Inner class to make easier for sorting
		 * 
		 * @author shaikat
		 */
		class FitnessOfIndividual {
			private double fitness;
			private int index;
			private boolean isParent;

			public FitnessOfIndividual(double fitness, int index, boolean isParent) {
				this.fitness = fitness;
				this.index = index;
				this.isParent = isParent;
			}
		}

		// fitnessArray: save all the fitness of parent (population) and child (new population)
		ArrayList<FitnessOfIndividual> fitnessArray = new ArrayList<FitnessOfIndividual>();
		for (int i = 0; i < populationSize; i++) {
			fitnessArray.add(new FitnessOfIndividual(population.get(i).getFitness(), i, true));
			fitnessArray.add(new FitnessOfIndividual(newPopulation.get(i).getFitness(), i, false));
		}

		// sort the whole population according to the fitness value (descending order)
		Collections.sort(fitnessArray, new Comparator<FitnessOfIndividual>() {
			public int compare(FitnessOfIndividual o1, FitnessOfIndividual o2) {
				return Double.compare(o1.fitness, o2.fitness);
			}
		});

		// copy population to old population
		List<Individual> oldPopulation = new ArrayList<Individual>(nodes.length);

		for (int i = 0; i < populationSize; i++) {
			oldPopulation.add(population.get(i));
		}

		population.clear();

		// copy back to population: best populationSize individuals
		for (int i = 0; i < populationSize; i++) {
			if (fitnessArray.get(i).isParent == true) {
				population.add(oldPopulation.get(fitnessArray.get(i).index));
			} else {
				population.add(newPopulation.get(fitnessArray.get(i).index));
			}
		}

	}

	public String getMapperId() {
		return MAPPER_ID;
	}

	private void runGaSteps() {
		// position of parent1 and parent2 in the population
		int posOfParent1, posOfParent2;

		doInitPopulation();

		while (currentNoOfGeneration < noOfGenerationToRun) {
			// clear new population
			newPopulation.clear();

			for (int i = 0; i < populationSize / 2; i++) {
				posOfParent1 = tournamentSelection(tournamentSize);
				posOfParent2 = tournamentSelection(tournamentSize);

				doPositionBasedCrossOver(posOfParent1, posOfParent2);
				doMutationv1();
				// doMutationv2();

				insertCurrentChildrenIntoNewPopulation();
			}

			createPopulationElitismV2();

			if (currentNoOfGeneration > 0 && currentNoOfGeneration % 10 == 0) {
				logger.info("Finished " + currentNoOfGeneration + " generations");
			}
			currentNoOfGeneration++;
		}

	}

	@Override
	protected void doBeforeMapping() {
		;
	}

	@Override
	protected void doMapping() {
		runGaSteps();
		
		// return the best mapping found
		for (int i = 0; i < nodes.length; i++) {
			nodes[i].setCore("-1");
		}
		for (int i = 0; i < cores.length; i++) {
			cores[i].setNodeId(-1);
		}
		if (logger.isDebugEnabled()) {
			for (int i = 0; i < population.size(); i++) {
				logger.debug("Individual " + i + " has fitness " + population.get(i).getFitness());
			}
		}
		for (int i = 0; i < nodes.length; i++) {
			String coreAsString = Integer.toString(population.get(0).getGenes()[i]);
			nodes[i].setCore(coreAsString);
			if (!"-1".equals(coreAsString)) {
				cores[Integer.valueOf(coreAsString)].setNodeId(i);
			}
		}
		if (buildRoutingTable) {
			programRouters();
		}

	}

	@Override
	protected void doBeforeSavingMapping() {
		;
	}

	public static void main(String args[]) throws TooFewNocNodesException,
			IOException, JAXBException, GenticAlgorithmInputException, ParseException {
		final int applicationBandwithRequirement = 3; // a multiple of the communication volume
		final double linkBandwidth = 256E9;
		final float switchEBit = 0.284f;
		final float linkEBit = 0.449f;
		final float bufReadEBit = 1.056f;
		final float bufWriteEBit = 2.831f;
		
		final int defaultPopulationSize = 100;
		final int defaultNoOfGeneration = 50;
		final int defaultCrossoverPr = 85;
		final int defaultMutationPr = 5;
		
		// FIXME make these parameters
		populationSize = defaultPopulationSize;
		noOfGenerationToRun = defaultNoOfGeneration;
		crossoverPr = defaultCrossoverPr;
		mutationPr = defaultMutationPr;
		
//		Options options = new Options();
//		options.addOption("p", "population-size", true, "the population size");
//		options.addOption("g", "generations", true, "the number of generations");
//		options.addOption("x", "crossover-probability", true, "crossover probability (%)");
//		options.addOption("m", "mutation-probability", true, "mutation probability (%)");
//		
//		CommandLineParser parser = new PosixParser();
//		CommandLine cmd = parser.parse(options, args, true);
//		
//		populationSize = Integer.valueOf(cmd.getOptionValue("p", Integer.toString(defaultPopulationSize)));
//		noOfGenerationToRun = Integer.valueOf(cmd.getOptionValue("g", Integer.toString(defaultNoOfGeneration)));
//		crossoverPr = Integer.valueOf(cmd.getOptionValue("x", Integer.toString(defaultCrossoverPr)));
//		mutationPr = Integer.valueOf(cmd.getOptionValue("m", Integer.toString(defaultMutationPr)));
		// here 20 percent of the total population size is used as tournament size
		tournamentSize = (populationSize * 20) / 100;
		
		MapperInputProcessor mapperInputProcessor = new MapperInputProcessor() {
			
			@Override
			public void useMapper(String benchmarkFilePath, String benchmarkName,
					String ctgId, String apcgId, List<CtgType> ctgTypes,
					List<ApcgType> apcgTypes, boolean doRouting, Long seed) throws JAXBException,
					TooFewNocNodesException, FileNotFoundException {
				logger.info("Using an energy aware genetic algorithm mapper for "
						+ benchmarkFilePath + "ctg-" + ctgId + " (APCG " + apcgId + ")");
				
				EnergyAwareGeneticAlgorithmMapper eagaMapper;
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
						"routing",
						"seed"};
				String values[] = new String[] {
						Integer.toString(applicationBandwithRequirement),
						Double.toString(linkBandwidth),
						Float.toString(switchEBit), Float.toString(linkEBit),
						Float.toString(bufReadEBit),
						Float.toString(bufWriteEBit),
						null,
						seed == null ? null : Long.toString(seed)};
				if (doRouting) {
					values[values.length - 2] = "true";
					MapperDatabase.getInstance().setParameters(parameters, values);
					
					// with routing
					eagaMapper = new EnergyAwareGeneticAlgorithmMapper(
							benchmarkName, ctgId, apcgId,
							topologyName, meshSize, new File(
									topologyDir), cores, linkBandwidth,
							true, LegalTurnSet.ODD_EVEN, bufReadEBit,
							bufWriteEBit, switchEBit, linkEBit, seed);
				} else {
					values[values.length - 2] = "false";
					MapperDatabase.getInstance().setParameters(parameters, values);
					
					// without routing
					eagaMapper = new EnergyAwareGeneticAlgorithmMapper(
							benchmarkName, ctgId, apcgId,
							topologyName, meshSize, new File(
									topologyDir), cores, linkBandwidth,
							switchEBit, linkEBit, seed);
				}
	
	//			// read the input data from a traffic.config file (NoCmap style)
	//			eagaMapper(
	//					"telecom-mocsyn-16tile-selectedpe.traffic.config",
	//					linkBandwidth);
				
				for (int k = 0; k < apcgTypes.size(); k++) {
					// read the input data using the Unified Framework's XML interface
					eagaMapper.parseApcg(apcgTypes.get(k), ctgTypes.get(k), applicationBandwithRequirement);
				}
				
	//			// This is just for checking that bbMapper.parseTrafficConfig(...)
	//			// and parseApcg(...) have the same effect
	//			eagaMapper.printCores();
	
				String mappingXml = eagaMapper.map();
				File dir = new File(benchmarkFilePath + "ctg-" + ctgId);
				dir.mkdirs();
				String routing = "";
				if (doRouting) {
					routing = "_routing";
				}
				String mappingXmlFilePath = benchmarkFilePath + "ctg-" + ctgId
						+ File.separator + "mapping-" + apcgId + "_"
						+ eagaMapper.getMapperId() + routing + ".xml";
				PrintWriter pw = new PrintWriter(mappingXmlFilePath);
				logger.info("Saving the mapping XML file" + mappingXmlFilePath);
				pw.write(mappingXml);
				pw.close();
	
				logger.info("The generated mapping is:");
				eagaMapper.printCurrentMapping();
				
				eagaMapper.analyzeIt();
			}
		};
		mapperInputProcessor.processInput(args);
	}

}