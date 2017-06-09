/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.apache.sysml.runtime.matrix.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math3.random.Well1024a;
import org.apache.sysml.hops.DataGenOp;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.parfor.util.IDSequence;
import org.apache.sysml.runtime.util.NormalPRNGenerator;
import org.apache.sysml.runtime.util.PRNGenerator;
import org.apache.sysml.runtime.util.PoissonPRNGenerator;
import org.apache.sysml.runtime.util.UniformPRNGenerator;
import org.apache.sysml.runtime.util.UtilFunctions;

public class LibMatrixDatagen 
{
	private static final Log LOG = LogFactory.getLog(LibMatrixDatagen.class.getName());
	private static final long PAR_NUMCELL_THRESHOLD = 512*1024; //Min 500k elements
	
	private static IDSequence _seqRandInput = new IDSequence(); 
	
	private LibMatrixDatagen() {
		//prevent instantiation via private constructor
	}

	public static boolean isShortcutRandOperation( double min, double max, double sparsity, RandomMatrixGenerator.PDF pdf )
	{
		return pdf == RandomMatrixGenerator.PDF.UNIFORM
			   && (  ( min == 0.0 && max == 0.0 ) //all zeros
				   ||( sparsity==1.0d && min == max )); //equal values
	}

	public static double updateSeqIncr(double seq_from, double seq_to, double seq_incr) {
		//handle default 1 to -1 for special case of from>to
		return (seq_from>seq_to && seq_incr==1)? -1 : seq_incr;
	}

	public static String generateUniqueSeedPath( String basedir ) {
		return basedir + "tmp" + _seqRandInput.getNextID() + ".randinput";
	}
	
	/**
	 * A matrix of random numbers is generated by using multiple seeds, one for each 
	 * block. Such block-level seeds are produced via Well equidistributed long-period linear 
	 * generator (Well1024a). For a given seed, this function sets up the block-level seeds.
	 * 
	 * This function is invoked from both CP (RandCPInstruction.processInstruction()) 
	 * as well as MR (RandMR.java while setting up the Rand job).
	 * 
	 * @param seed seed for random generator
	 * @return Well1024a pseudo-random number generator
	 */
	public static Well1024a setupSeedsForRand(long seed) 
	{
		long lSeed = (seed == DataGenOp.UNSPECIFIED_SEED ? DataGenOp.generateRandomSeed() : seed);
		LOG.trace("Setting up RandSeeds with initial seed = "+lSeed+".");

		Random random=new Random(lSeed);
		Well1024a bigrand=new Well1024a();
		//random.setSeed(lSeed);
		int[] seeds=new int[32];
		for(int s=0; s<seeds.length; s++)
			seeds[s]=random.nextInt();
		bigrand.setSeed(seeds);
		
		return bigrand;
	}

	public static LongStream computeNNZperBlock(long nrow, long ncol, int brlen, int bclen, double sparsity) 
		throws DMLRuntimeException 
	{
		long lnumBlocks = (long) (Math.ceil((double)nrow/brlen) * Math.ceil((double)ncol/bclen));
		
		//sanity check max number of blocks (before cast to avoid overflow)
		if ( lnumBlocks > Integer.MAX_VALUE ) {
			throw new DMLRuntimeException("A random matrix of size [" + nrow + "," + ncol + "] can not be created. "
					+ "Number of blocks ("+lnumBlocks+") exceeds the maximum integer size. Try to increase the block size.");
		}

		int numBlocks = (int) lnumBlocks;
		int numColBlocks = (int) Math.ceil((double)ncol/bclen);
		long nnz = (long) Math.ceil (nrow * (ncol*sparsity));
		
		if( nnz < numBlocks ) {
			//#1: ultra-sparse random number generation
			//nnz per block: 1 with probability P = nnz/numBlocks, 0 with probability 1-P
			//(note: this is an unbiased generator that, however, will never generate more than 
			//one non-zero per block, but it uses weights to account for different block sizes)
			double P = (double) nnz / numBlocks;
			Random runif = new Random(System.nanoTime());
			return LongStream.range(0, numBlocks).map( i -> {
				double lP = P / (brlen*bclen) *
					UtilFunctions.computeBlockSize(nrow, 1 + i / numColBlocks, brlen) *
					UtilFunctions.computeBlockSize(ncol, 1 + i % numColBlocks, bclen);
				return (runif.nextDouble() <= lP) ? 1 : 0;
			});
		}
		else {
			//#2: dense/sparse random number generation
			//nnz per block: lrlen * lclen * sparsity (note: this is a biased generator 
			//that might actually create fewer but never more non zeros than expected)
			return LongStream.range(0, numBlocks).map( i -> (long)(sparsity * 
				UtilFunctions.computeBlockSize(nrow, 1 + i / numColBlocks, brlen) *
				UtilFunctions.computeBlockSize(ncol, 1 + i % numColBlocks, bclen)));
		}
	}

    public static RandomMatrixGenerator createRandomMatrixGenerator(String pdfStr, int r, int c, int rpb, int cpb, double sp, double min, double max, String distParams)
    	throws DMLRuntimeException
    {
		RandomMatrixGenerator.PDF pdf = RandomMatrixGenerator.PDF.valueOf(pdfStr.toUpperCase());
		RandomMatrixGenerator rgen = null;
    	switch (pdf) {
		case UNIFORM:
			rgen = new RandomMatrixGenerator(pdf, r, c, rpb, cpb, sp, min, max);
			break;
		case NORMAL:
			rgen = new RandomMatrixGenerator(pdf, r, c, rpb, cpb, sp);
			break;
		case POISSON:
			double mean = Double.NaN;
			try {
				mean = Double.parseDouble(distParams);
			} catch (NumberFormatException e) {
				throw new DMLRuntimeException("Failed to parse Poisson distribution parameter: " + distParams);
			}
			rgen = new RandomMatrixGenerator(pdf, r, c, rpb, cpb, sp, min, max, mean);
			break;
		default:
			throw new DMLRuntimeException("Unsupported probability distribution \"" + pdf + "\" in rand() -- it must be one of \"uniform\", \"normal\", or \"poisson\"");

		}
    	return rgen;
    }
	
	/**
	 * Function to generate a matrix of random numbers. This is invoked both
	 * from CP as well as from MR. In case of CP, it generates an entire matrix
	 * block-by-block. A <code>bigrand</code> is passed so that block-level
	 * seeds are generated internally. In case of MR, it generates a single
	 * block for given block-level seed <code>bSeed</code>.
	 * 
	 * When pdf="uniform", cell values are drawn from uniform distribution in
	 * range <code>[min,max]</code>.
	 * 
	 * When pdf="normal", cell values are drawn from standard normal
	 * distribution N(0,1). The range of generated values will always be
	 * (-Inf,+Inf).
	 * 
     * @param out output matrix block
     * @param rgen random matrix generator
     * @param nnzInBlocks number of non-zeros in blocks
     * @param bigrand Well1024a pseudo-random number generator
     * @param bSeed seed for random generator
     * @throws DMLRuntimeException if DMLRuntimeException occurs
     */
	public static void generateRandomMatrix( MatrixBlock out, RandomMatrixGenerator rgen, LongStream nnzInBlocks, 
							Well1024a bigrand, long bSeed ) 
		throws DMLRuntimeException
	{
		boolean invokedFromCP = (bigrand!=null || nnzInBlocks==null);
		int rows = rgen._rows;
		int cols = rgen._cols;
		int rpb = rgen._rowsPerBlock;
		int cpb = rgen._colsPerBlock;
		double sparsity = rgen._sparsity;
		
		// sanity check valid dimensions and sparsity
		checkMatrixDimensionsAndSparsity(rows, cols, sparsity);
		
		/*
		 * Setup min and max for distributions other than "uniform". Min and Max
		 * are set up in such a way that the usual logic of
		 * (max-min)*prng.nextDouble() is still valid. This is done primarily to
		 * share the same code across different distributions.
		 */
		double min = rgen._pdf == RandomMatrixGenerator.PDF.UNIFORM ? rgen._min : 0;
		double max = rgen._pdf == RandomMatrixGenerator.PDF.UNIFORM ? rgen._max : 1;
		
		// Special case shortcuts for efficiency
		if ( rgen._pdf == RandomMatrixGenerator.PDF.UNIFORM) {
			if ( min == 0.0 && max == 0.0 ) { //all zeros
				out.reset(rows, cols, true);
				return;
			} 
			else if( sparsity==1.0d && (min == max  //equal values, dense
					|| (Double.isNaN(min) && Double.isNaN(max))) ) { //min == max == NaN
				out.reset(rows, cols, min); 
				return;
			}
		}
		
		// collect nnz stream for multiple consumptions
		long[] lnnzInBlocks = nnzInBlocks.toArray();
		
		// Determine the sparsity of output matrix
		// if invoked from CP: estimated NNZ is for entire matrix (nnz=0, if 0 initialized)
		// if invoked from MR: estimated NNZ is for one block
		final long estnnz = (invokedFromCP ? ((min==0.0 && max==0.0)? 0 : 
				(long)(sparsity * rows * cols)) : lnnzInBlocks[0]);
		boolean lsparse = MatrixBlock.evalSparseFormatInMemory( rows, cols, estnnz );
		out.reset(rows, cols, lsparse);
		
		// Allocate memory
		//note: individual sparse rows are allocated on demand,
		//for consistency with memory estimates and prevent OOMs.
		if( out.sparse )
			out.allocateSparseRowsBlock();
		else
			out.allocateDenseBlock();	
		
		int nrb = (int) Math.ceil((double)rows/rpb);
		int ncb = (int) Math.ceil((double)cols/cpb);
		long[] seeds = invokedFromCP ? generateSeedsForCP(bigrand, nrb, ncb) : null;
		
		genRandomNumbers(invokedFromCP, 0, nrb, 0, ncb, out, rgen, lnnzInBlocks, bSeed, seeds);
		
		out.recomputeNonZeros();
	}
	
	/**
	 * Function to generate a matrix of random numbers. This is invoked both
	 * from CP as well as from MR. In case of CP, it generates an entire matrix
	 * block-by-block. A <code>bigrand</code> is passed so that block-level
	 * seeds are generated internally. In case of MR, it generates a single
	 * block for given block-level seed <code>bSeed</code>.
	 * 
	 * When pdf="uniform", cell values are drawn from uniform distribution in
	 * range <code>[min,max]</code>.
	 * 
	 * When pdf="normal", cell values are drawn from standard normal
	 * distribution N(0,1). The range of generated values will always be
	 * (-Inf,+Inf).
	 * 
	 * 
     * @param out output matrix block
     * @param rgen random matrix generator
     * @param nnzInBlocks number of non-zeros in blocks
     * @param bigrand Well1024a pseudo-random number generator
     * @param bSeed seed for random generator
     * @param k ?
     * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	public static void generateRandomMatrix( MatrixBlock out, RandomMatrixGenerator rgen, LongStream nnzInBlocks, 
			Well1024a bigrand, long bSeed, int k ) 
		throws DMLRuntimeException	
	{	
		int rows = rgen._rows;
		int cols = rgen._cols;
		int rpb = rgen._rowsPerBlock;
		int cpb = rgen._colsPerBlock;
		double sparsity = rgen._sparsity;
		
		//sanity check valid dimensions and sparsity
		checkMatrixDimensionsAndSparsity(rows, cols, sparsity);
				
		/*
		 * Setup min and max for distributions other than "uniform". Min and Max
		 * are set up in such a way that the usual logic of
		 * (max-min)*prng.nextDouble() is still valid. This is done primarily to
		 * share the same code across different distributions.
		 */
		double min = rgen._pdf == RandomMatrixGenerator.PDF.UNIFORM ? rgen._min : 0;
		double max = rgen._pdf == RandomMatrixGenerator.PDF.UNIFORM ? rgen._max : 1;
		
		//determine the sparsity of output matrix (multi-threaded always invoked from CP):
		//estimated NNZ is for entire matrix (nnz=0, if 0 initialized)
		final long estnnz = ((min==0.0 && max==0.0) ? 0 : (long)(sparsity * rows * cols));
		boolean lsparse = MatrixBlock.evalSparseFormatInMemory( rows, cols, estnnz );
		
		//fallback to sequential if single rowblock or too few cells or if MatrixBlock is not thread safe
		if( k<=1 || (rows <= rpb && lsparse) || (long)rows*cols < PAR_NUMCELL_THRESHOLD 
			|| !MatrixBlock.isThreadSafe(lsparse) ) {
			generateRandomMatrix(out, rgen, nnzInBlocks, bigrand, bSeed);
			return;
		}

		//special case shortcuts for efficiency
		if ( rgen._pdf == RandomMatrixGenerator.PDF.UNIFORM) {
			if ( min == 0.0 && max == 0.0 ) { //all zeros
				out.reset(rows, cols, false);
				return;
			} 
			else if( sparsity==1.0d && min == max ) { //equal values
				out.reset(rows, cols, min); 
				return;
			}
		}
		
		//allocate memory
		//note: individual sparse rows are allocated on demand,
		//for consistency with memory estimates and prevent OOMs.
		out.reset(rows, cols, lsparse);
		if( out.sparse )
			out.allocateSparseRowsBlock();
		else
			out.allocateDenseBlock();	
	
		int nrb = (int) Math.ceil((double)rows/rpb);
		int ncb = (int) Math.ceil((double)cols/cpb);
		
		//default: parallelization over row blocks, fallback to parallelization
		//over column blocks if possible and necessary (higher degree of par)
		boolean parcol = (!out.sparse && nrb<k && ncb>nrb);
		int parnb = parcol ? ncb : nrb;
		
		//generate seeds independent of parallelizations
		long[] seeds = generateSeedsForCP(bigrand, nrb, ncb);
		
		// collect nnz stream for multiple consumptions
		long[] lnnzInBlocks = nnzInBlocks.toArray();
		
		try 
		{
			ExecutorService pool = Executors.newFixedThreadPool(k);
			ArrayList<RandTask> tasks = new ArrayList<RandTask>();
			int blklen = ((int)(Math.ceil((double)parnb/k)));
			for( int i=0; i<k & i*blklen<parnb; i++ ) {
				int rl = parcol ? 0 : i*blklen; 
				int ru = parcol ? nrb : Math.min((i+1)*blklen, parnb);
				int cl = parcol ? i*blklen : 0; 
				int cu = parcol ? Math.min((i+1)*blklen, parnb) : ncb;
				long[] lseeds = sliceSeedsForCP(seeds, rl, ru, cl, cu, nrb, ncb);
				tasks.add(new RandTask(rl, ru, cl, cu, out, 
						rgen, lnnzInBlocks, bSeed, lseeds) );	
			}
			List<Future<Object>> ret = pool.invokeAll(tasks);
			pool.shutdown();
			
			//exception propagation in case not all tasks successful
			for(Future<Object> rc : ret) 
				rc.get();
		} 
		catch (Exception e) {
			throw new DMLRuntimeException(e);
		}	
		
		out.recomputeNonZeros();
	}
	
	/**
	 * Method to generate a sequence according to the given parameters. The
	 * generated sequence is always in dense format.
	 * 
	 * Both end points specified <code>from</code> and <code>to</code> must be
	 * included in the generated sequence i.e., [from,to] both inclusive. Note
	 * that, <code>to</code> is included only if (to-from) is perfectly
	 * divisible by <code>incr</code>.
	 * 
	 * For example, seq(0,1,0.5) generates (0.0 0.5 1.0) 
	 *      whereas seq(0,1,0.6) generates (0.0 0.6) but not (0.0 0.6 1.0)
	 * 
	 * @param out output matrix block
	 * @param from lower end point
	 * @param to upper end point
	 * @param incr increment value
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	public static void generateSequence(MatrixBlock out, double from, double to, double incr) 
		throws DMLRuntimeException 
	{
		//check valid increment value
		if( (from > to && incr > 0) || incr == 0 )
			throw new DMLRuntimeException("Wrong sequence increment: from="+from+", to="+to+ ", incr="+incr);
		
		//prepare output matrix
		int rows = 1 + (int)Math.floor((to-from)/incr);
		int cols = 1; // sequence vector always dense
		out.reset(rows, cols, false);
		out.allocateDenseBlock();
	
		//compute sequence data
		double[] c = out.denseBlock; 		
		double cur = from;
		for(int i=0; i < rows; i++) {
			c[i] = cur;
			cur += incr;
		}
		
		out.recomputeNonZeros();
	}

	
		
	/**
     * Generates a sample of size <code>size</code> from a range of values [1,range].
     * <code>replace</code> defines if sampling is done with or without replacement.
	 * 
	 * @param out output matrix block
	 * @param range range upper bound
	 * @param size sample size
	 * @param replace if true, sample with replacement
	 * @param seed seed for random generator
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	public static void generateSample(MatrixBlock out, long range, int size, boolean replace, long seed)
		throws DMLRuntimeException 
	{
		//set meta data and allocate dense block
		out.reset(size, 1, false);
		out.allocateDenseBlock();
		seed = (seed == -1 ? System.nanoTime() : seed);
		
		if ( !replace ) 
		{
			// reservoir sampling
			
			for(int i=1; i <= size; i++) 
				out.setValueDenseUnsafe(i-1, 0, i );
			
			Random rand = new Random(seed);
			for(int i=size+1; i <= range; i++) 
			{
				if(rand.nextInt(i) < size)
					out.setValueDenseUnsafe( rand.nextInt(size), 0, i );
			}
			
			// randomize the sample (Algorithm P from Knuth's ACP)
			// -- needed especially when the differnce between range and size is small)
			double tmp;
			int idx;
			for(int i=size-1; i >= 1; i--) 
			{
				idx = rand.nextInt(i);
				// swap i^th and idx^th entries
				tmp = out.getValueDenseUnsafe(idx, 0);
				out.setValueDenseUnsafe(idx, 0, out.getValueDenseUnsafe(i, 0));
				out.setValueDenseUnsafe(i, 0, tmp);
			}
	
		}
		else 
		{
			Random r = new Random(seed);
			for(int i=0; i < size; i++) 
				out.setValueDenseUnsafe(i, 0, 1+nextLong(r, range) );
		}
		
		out.recomputeNonZeros();
		out.examSparsity();
	}

	private static long[] generateSeedsForCP(Well1024a bigrand, int nrb, int ncb)
	{
		int numBlocks = nrb * ncb;
		long[] seeds = new long[numBlocks];
		for( int l = 0; l < numBlocks; l++ )
			seeds[l] = bigrand.nextLong();
		
		return seeds;
	}

	private static long[] sliceSeedsForCP(long[] seeds, int rl, int ru, int cl, int cu, int nrb, int ncb)
	{
		int numBlocks = (ru-rl) * (cu-cl);
		long[] lseeds = new long[numBlocks];
		for( int i = rl; i < ru; i++ )
			System.arraycopy(seeds, i*ncb+cl, lseeds, (i-rl)*(cu-cl), cu-cl);
		
		return lseeds;
	}

	private static void genRandomNumbers(boolean invokedFromCP, int rl, int ru, int cl, int cu, MatrixBlock out, RandomMatrixGenerator rgen, long[] nnzInBlocks, long bSeed, long[] seeds) 
		throws DMLRuntimeException 
	{
		int rows = rgen._rows;
		int cols = rgen._cols;
		int rpb = rgen._rowsPerBlock;
		int cpb = rgen._colsPerBlock;
		double sparsity = rgen._sparsity;
		PRNGenerator valuePRNG = rgen._valuePRNG;
		double min = rgen._pdf == RandomMatrixGenerator.PDF.UNIFORM ? rgen._min : 0;
		double max = rgen._pdf == RandomMatrixGenerator.PDF.UNIFORM ? rgen._max : 1;
		double range = max - min;
		int clen = out.clen;
		int estimatedNNzsPerRow = out.estimatedNNzsPerRow;
		
		int nrb = (int) Math.ceil((double)rows/rpb);
		int ncb = (int) Math.ceil((double)cols/cpb);
		int blockID = rl*ncb; //used for sparse
		int counter = 0;

		// Setup Pseudo Random Number Generator for cell values based on 'pdf'.
		if (valuePRNG == null) {
			switch (rgen._pdf) {
			case UNIFORM:
				valuePRNG = new UniformPRNGenerator();
				break;
			case NORMAL:
				valuePRNG = new NormalPRNGenerator();
				break;
			case POISSON:
				valuePRNG = new PoissonPRNGenerator();
				break;
			default:
				throw new DMLRuntimeException("Unsupported distribution function for Rand: " + rgen._pdf);
			}
		}
		
		// loop through row-block indices
		for(int rbi = rl; rbi < ru; rbi++) {
			int blockrows = (rbi == nrb-1 ? (rows-rbi*rpb) : rpb);
			int rowoffset = rbi*rpb;

			// loop through column-block indices
			for(int cbj = cl; cbj < cu; cbj++, blockID++) {
				int blockcols = (cbj == ncb-1 ? (cols-cbj*cpb) : cpb);
				int coloffset = cbj*cpb;
				
				// select the appropriate block-level seed and init PRNG
				long seed = !invokedFromCP ?  bSeed : seeds[counter++]; 
				valuePRNG.setSeed(seed);
				
				// Initialize the PRNGenerator for determining cells that contain a non-zero value
				// Note that, "pdf" parameter applies only to cell values and the individual cells 
				// are always selected uniformly at random.
				UniformPRNGenerator nnzPRNG = new UniformPRNGenerator(seed);

				// block-level sparsity, which may differ from overall sparsity in the matrix.
				// (e.g., border blocks may fall under skinny matrix turn point, in CP this would be 
				// irrelevant but we need to ensure consistency with MR)
				boolean localSparse = MatrixBlock.evalSparseFormatInMemory(blockrows, blockcols, nnzInBlocks[blockID] ); //(long)(sparsity*blockrows*blockcols));  
				if ( localSparse ) {
					SparseBlock c = out.sparseBlock;
					
					int idx = 0;  // takes values in range [1, brlen*bclen] (both ends including)
					int ridx=0, cidx=0; // idx translates into (ridx, cidx) entry within the block
					int skip = -1;
					double p = sparsity;
			        
					// Prob [k-1 zeros before a nonzero] = Prob [k-1 < log(uniform)/log(1-p) < k] = p*(1-p)^(k-1), where p=sparsity
					double log1mp = Math.log(1-p);
					long blocksize = blockrows*blockcols;
					while(idx < blocksize) {
						skip = (int) Math.ceil( Math.log(nnzPRNG.nextDouble())/log1mp )-1;
						idx = idx+skip+1;

						if ( idx > blocksize)
							break;
						
						// translate idx into (r,c) within the block
						ridx = (idx-1)/blockcols;
						cidx = (idx-1)%blockcols;
						double val = min + (range * valuePRNG.nextDouble());
						c.allocate(rowoffset+ridx, estimatedNNzsPerRow, clen);
						c.append(rowoffset+ridx, coloffset+cidx, val);
					}
				}
				else {
					if (sparsity == 1.0) {
						double[] c = out.denseBlock;
						int cix = rowoffset*cols + coloffset;
						for(int ii = 0; ii < blockrows; ii++, cix+=cols)
							for(int jj = 0; jj < blockcols; jj++)
								c[cix+jj] = min + (range * valuePRNG.nextDouble());
					}
					else {
						if (out.sparse ) {
							/* This case evaluated only when this function is invoked from CP. 
							 * In this case:
							 *     sparse=true -> entire matrix is in sparse format and hence denseBlock=null
							 *     localSparse=false -> local block is dense, and hence on MR side a denseBlock will be allocated
							 * i.e., we need to generate data in a dense-style but set values in sparseRows
							 * 
							 */
							// In this case, entire matrix is in sparse format but the current block is dense
							SparseBlock c = out.sparseBlock;
							for(int ii=0; ii < blockrows; ii++) {
								for(int jj=0; jj < blockcols; jj++) {
									if(nnzPRNG.nextDouble() <= sparsity) {
										double val = min + (range * valuePRNG.nextDouble());
										c.allocate(ii+rowoffset, estimatedNNzsPerRow, clen);
										c.append(ii+rowoffset, jj+coloffset, val);
									}
								}
							}
						}
						else {
							double[] c = out.denseBlock;
							int cix = rowoffset*cols + coloffset;
							for(int ii = 0; ii < blockrows; ii++, cix+=cols)
								for(int jj = 0; jj < blockcols; jj++)
									if(nnzPRNG.nextDouble() <= sparsity)
										c[cix+jj] =  min + (range * valuePRNG.nextDouble());
						}
					}
				} // sparse or dense 
			} // cbj
		} // rbi	
	}

	private static void checkMatrixDimensionsAndSparsity(int rows, int cols, double sp) 
		throws DMLRuntimeException
	{
		if( rows <= 0 || cols <= 0 || sp < 0 || sp > 1)
			throw new DMLRuntimeException("Invalid matrix characteristics: "+rows+"x"+cols+", "+sp);
	}
	
	// modified version of java.util.nextInt
    private static long nextLong(Random r, long n) {
        if (n <= 0)
            throw new IllegalArgumentException("n must be positive");

        //if ((n & -n) == n)  // i.e., n is a power of 2
        //    return ((n * (long)r.nextLong()) >> 31);

        long bits, val;
        do {
            bits = (r.nextLong() << 1) >>> 1;
            val = bits % n;
        } while (bits - val + (n-1) < 0L);
        return val;
    }

	private static class RandTask implements Callable<Object> 
	{
		private int _rl = -1;
		private int _ru = -1;
		private int _cl = -1;
		private int _cu = -1;
		private MatrixBlock _out = null;
		private RandomMatrixGenerator _rgen = new RandomMatrixGenerator();
		private long[] _nnzInBlocks = null;
		private long _bSeed = 0;
		private long[] _seeds = null;
		
		public RandTask(int rl, int ru, int cl, int cu, MatrixBlock out, RandomMatrixGenerator rgen, long[] nnzInBlocks, long bSeed, long[] seeds) 
			throws DMLRuntimeException 
		{
			_rl = rl;
			_ru = ru;
			_cl = cl;
			_cu = cu;
			_out = out;
			_rgen.init(rgen._pdf, rgen._rows, rgen._cols, rgen._rowsPerBlock, rgen._colsPerBlock, rgen._sparsity, rgen._min, rgen._max, rgen._mean);
			_nnzInBlocks = nnzInBlocks;
			_bSeed = bSeed;
			_seeds = seeds;
		}

		@Override		
		public Object call() throws Exception
		{
			genRandomNumbers(true, _rl, _ru, _cl, _cu, _out, _rgen, _nnzInBlocks, _bSeed, _seeds);
			return null;
		}
	}
}
