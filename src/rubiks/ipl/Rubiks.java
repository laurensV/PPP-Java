package rubiks.ipl;

import ibis.ipl.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Solver for rubik's cube puzzle.
 * 
 * @author Niels Drost, Timo van Kessel
 * 
 */
public class Rubiks implements MessageUpcall {

    PortType replyPortType = new PortType(PortType.COMMUNICATION_RELIABLE,
    PortType.SERIALIZATION_OBJECT, PortType.RECEIVE_EXPLICIT,
    PortType.CONNECTION_ONE_TO_ONE);

    PortType requestPortType = new PortType(PortType.COMMUNICATION_RELIABLE,
    PortType.SERIALIZATION_OBJECT, PortType.RECEIVE_AUTO_UPCALLS,
    PortType.CONNECTION_MANY_TO_ONE);

    IbisCapabilities ibisCapabilities = new IbisCapabilities(
    IbisCapabilities.ELECTIONS_STRICT, IbisCapabilities.CLOSED_WORLD);

    public static final boolean PRINT_SOLUTION = false;

    private ArrayList<Cube> jobQueue;
    private HashMap<IbisIdentifier, SendPort> workers;
    private boolean queueReady;
    private Object queueLock = new Object();
    private Object waitForWorkers = new Object();
    private Ibis ibis;
    private boolean solved;
    private AtomicInteger activeWorkers;
	private AtomicInteger solutions;

    private void generateJobs(Cube cube, boolean moreJobs) {
		Cube[] cubes, children;
		// cache used for cube objects. Doing new Cube() for every move
        // overloads the garbage collector
        CubeCache cache = new CubeCache(cube.getSize());
    	// generate all possible cubes from this one by twisting it in
        // every possible way. Gets new objects from the cache
        cubes = cube.generateChildren(cache);
    	if (moreJobs){
        	for (Cube child : cubes) {
        		children = child.generateChildren(cache);
        		jobQueue.addAll(Arrays.asList(children));
    		}
    	} else {
        	jobQueue.addAll(Arrays.asList(cubes));
    	}
    	
        // unlock threads (worker requests) waiting for the queue
	    synchronized (queueLock){ 
	        queueReady = true; 
	        queueLock.notifyAll(); 
	    } 
    }
    
    private void master(int size, int twists, int seed, String fileName) throws IOException {
       // System.out.println("I am the master");
        // initialize variables
        queueReady = false;
        solved = false;
        Cube cube = null;
        solutions = new AtomicInteger(0);
        activeWorkers = new AtomicInteger(0);
        solved = false;
        workers = new HashMap<IbisIdentifier, SendPort>();
    	jobQueue = new ArrayList<Cube>();

        // create cube
        if (fileName == null) {
            cube = new Cube(size, twists, seed);
        } else {
            try {
                cube = new Cube(fileName);
            } catch (Exception e) {
                System.err.println("Cannot load cube from file: " + e);
                System.exit(1);
            }
        }
        
        // print cube info
        System.out.println("Searching for solution for cube of size "
                + cube.getSize() + ", twists = " + twists + ", seed = " + seed);
        cube.print(System.out);
        System.out.flush();

        // Create a receive port and enable connections and message upcalls,
        // so workers can make requests.
        ReceivePort receiveRequestPort = ibis.createReceivePort(requestPortType, "master", this);
        receiveRequestPort.enableConnections();
        receiveRequestPort.enableMessageUpcalls();


        // solve the cube
        long start = System.currentTimeMillis();
        solve(cube);
        long end = System.currentTimeMillis();

        // notify waiting workers that cube is solved
        synchronized (queueLock) {
        	queueLock.notifyAll();
        }

        // NOTE: this is printed to standard error! The rest of the output is
        // constant for each set of parameters. Printing this to standard error
        // makes the output of standard out comparable with "diff"
        System.err.println("Solving cube took " + (end - start)
                + " milliseconds");
        // Close receive port
        receiveRequestPort.close();
    }


    /* Function called by Ibis to give us a newly arrived message.*/
    public void upcall(ReadMessage message) throws IOException, ClassNotFoundException {
    	SendPort sendReplyPort;
        int result = message.readInt();
        IbisIdentifier worker = message.origin().ibisIdentifier();

        // Finish message, so ibis can call this function again
        message.finish();

        // Check if there is an result from the worker from a previously given cube
        if(result != -1){
        	synchronized (this) {
				solutions.addAndGet(result);
				// Decrease the number of workers the master has to wait for
				activeWorkers.decrementAndGet();
				// notify the master
				this.notify();
			}
        }
        // Check if there is already a connection for a returning worker
        sendReplyPort = workers.get(worker);

        // create new connection for a new worker
        if(sendReplyPort == null){
	        try {
	        	// Create a port to send a cube back
				sendReplyPort = ibis.createSendPort(replyPortType);

		        // Connect to the port id of the worker
		        sendReplyPort.connect(worker, "reply");
			//System.out.println("worker connected!");
				workers.put(worker, sendReplyPort);
			} catch (IOException e) {
				e.printStackTrace(System.err);
			}
		}

        // create a reply message
        WriteMessage replyMessage = sendReplyPort.newMessage();

        Cube workerCube = null;

        if(!solved) {
	        while (workerCube == null){
	        	// wait for queue to be ready
		        synchronized (queueLock){
		            while(!queueReady){ 
		                try { 
		                    queueLock.wait(); 
		                } catch (InterruptedException e) {  
		                } 
		            } 
		        }
		        if(solved) {
		 			break;
		        }
		        synchronized (jobQueue) {
			        try { 
			            workerCube = jobQueue.remove(jobQueue.size() - 1);
			        } catch (Exception e){
			        	// queue is empty
			            queueReady = false;  
			        } 
		        }
		    }
		}
	    // Increase the number of workers the master has to wait for
	    activeWorkers.incrementAndGet();

       	replyMessage.writeObject(workerCube);
       	replyMessage.finish();
       	
       	// If workerCube equals null, we are done and we can close the connection
       	if(workerCube == null){
        	sendReplyPort.close();
    	}
    }

     private void worker(IbisIdentifier master) throws IOException {
        //System.out.println("I am a worker");
        int result = -1;
        // Create a send port for sending requests and connect.
        SendPort sendRequestPort = ibis.createSendPort(requestPortType);
        sendRequestPort.connect(master, "master");

        // Create a receive port for receiving replies from the master
        ReceivePort receiveReplyPort = ibis.createReceivePort(replyPortType, "reply");
        receiveReplyPort.enableConnections();
        while(true){
	        // Send request to master with identifier for receive port so the
	        // master knows where to send the reply to
	        WriteMessage request = sendRequestPort.newMessage();
	        request.writeInt(result);
	        request.finish();

	        // Get reply from master
	        ReadMessage reply = receiveReplyPort.receive();

	        // Get cube from reply
	        Cube myCube = null;
	        try {
	            myCube = (Cube) reply.readObject();
	           // System.out.println("Received a cube!");
	        } catch (ClassNotFoundException e) {
	            e.printStackTrace();
	        }

	        reply.finish();

	       	// If myCube equals null, we are done and we can close the connection
	        if(myCube == null){
				// Close ports
				sendRequestPort.close();
				receiveReplyPort.close();	
	            return;
	        }

	        /* solve my cube */
	        CubeCache cache = new CubeCache(myCube.getSize());
        	result = solutions(myCube, cache);
        }

     }

     private void run(String[] arguments) throws Exception {
        // default parameters of puzzle
        int size = 3;
        int twists = 11;
        int seed = 0;
        String fileName = null;

        // number of threads used to solve puzzle
        // (not used in sequential version)

        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i].equalsIgnoreCase("--size")) {
                i++;
                size = Integer.parseInt(arguments[i]);
            } else if (arguments[i].equalsIgnoreCase("--twists")) {
                i++;
                twists = Integer.parseInt(arguments[i]);
            } else if (arguments[i].equalsIgnoreCase("--seed")) {
                i++;
                seed = Integer.parseInt(arguments[i]);
            } else if (arguments[i].equalsIgnoreCase("--file")) {
                i++;
                fileName = arguments[i];
            } else if (arguments[i].equalsIgnoreCase("--help") || arguments[i].equalsIgnoreCase("-h")) {
                printUsage();
                System.exit(0);
            } else {
                System.err.println("unknown option : " + arguments[i]);
                printUsage();
                System.exit(1);
            }
        }
        
        // Create an ibis instance.
        ibis = IbisFactory.createIbis(ibisCapabilities, null, replyPortType, requestPortType);

        // Wait until all machines joined the pool
        ibis.registry().waitUntilPoolClosed();

        // Elect a master
        IbisIdentifier master = ibis.registry().elect("master");

        // If I am the master, run master, else run worker.
        if (master.equals(ibis.identifier())) {
           master(size, twists, seed, fileName);
        } else {
           worker(master);
        }

        // End ibis.
        ibis.end();
     }

    /**
     * Recursive function to find a solution for a given cube. Only searches to
     * the bound set in the cube object.
     * 
     * @param cube
     *            cube to solve
     * @param cache
     *            cache of cubes used for new cube objects
     * @return the number of solutions found
     */
    private static int solutions(Cube cube, CubeCache cache) {
        if (cube.isSolved()) {
            return 1;
        }

        if (cube.getTwists() >= cube.getBound()) {
            return 0;
        }

        // generate all possible cubes from this one by twisting it in
        // every possible way. Gets new objects from the cache
        Cube[] children = cube.generateChildren(cache);

        int result = 0;

        for (Cube child : children) {
            // recursion step
            int childSolutions = solutions(child, cache);
            if (childSolutions > 0) {
                result += childSolutions;
                if (PRINT_SOLUTION) {
                    child.print(System.err);
                }
            }
            // put child object in cache
            cache.put(child);
        }

        return result;
    }

    /**
     * Solves a Rubik's cube by iteratively searching for solutions with a
     * greater depth. This guarantees the optimal solution is found. Repeats all
     * work for the previous iteration each iteration though...
     * 
     * @param cube
     *            the cube to solve
     */
    private void solve(Cube cube) {
        int bound = 0;
        boolean moreJobs;
        System.out.print("Bound now:");

        while (solutions.get() == 0) {
        	moreJobs = false;
        	queueReady = false;
	       	bound++;
            cube.setBound(bound);
            if (bound > 1) {
            	moreJobs = true;
            } 
            generateJobs(cube, moreJobs);
        	System.out.print(" " + bound);

        	Cube myCube = null;

        	// master will also solve cubes from queue
        	while (queueReady){
	        	synchronized (jobQueue) {
			        try { 
			            myCube = jobQueue.remove(jobQueue.size() - 1);
			        } catch (Exception e){
			        	// queue is empty
			            queueReady = false;  
			        } 
		        }
	        	/* solve my cube */
		        CubeCache cache = new CubeCache(myCube.getSize());
	        	int result = solutions(myCube, cache);
	        	solutions.addAndGet(result);
       		}	

       		// queue is empty, wait for all results from workers
        	synchronized (this) {
        		while (activeWorkers.get() != 0) {
					try {
						this.wait();
					} catch (Exception e) {
					}
				}
        	}
        }
        queueReady = true;  
        solved = true;
        System.out.println();
        System.out.println("Solving cube possible in " + solutions.get() + " ways of "
                + bound + " steps");
    }

    public static void printUsage() {
        System.out.println("Rubiks Cube solver");
        System.out.println("");
        System.out
                .println("Does a number of random twists, then solves the rubiks cube with a simple");
        System.out
                .println(" brute-force approach. Can also take a file as input");
        System.out.println("");
        System.out.println("USAGE: Rubiks [OPTIONS]");
        System.out.println("");
        System.out.println("Options:");
        System.out.println("--size SIZE\t\tSize of cube (default: 3)");
        System.out
                .println("--twists TWISTS\t\tNumber of random twists (default: 11)");
        System.out
                .println("--seed SEED\t\tSeed of random generator (default: 0");
        System.out
                .println("--threads THREADS\t\tNumber of threads to use (default: 1, other values not supported by sequential version)");
        System.out.println("");
        System.out
                .println("--file FILE_NAME\t\tLoad cube from given file instead of generating it");
        System.out.println("");
    }

    /**
     * Main function.
     * 
     * @param arguments
     *            list of arguments
     */
    public static void main(String[] arguments) {
        try {
            new Rubiks().run(arguments);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

}
