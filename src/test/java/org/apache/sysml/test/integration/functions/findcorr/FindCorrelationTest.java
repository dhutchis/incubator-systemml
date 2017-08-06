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

package org.apache.sysml.test.integration.functions.findcorr;

import java.util.HashMap;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysml.api.DMLScript;
import org.apache.sysml.api.DMLScript.RUNTIME_PLATFORM;
import org.apache.sysml.lops.LopProperties.ExecType;
import org.apache.sysml.runtime.matrix.data.MatrixValue;
import org.apache.sysml.test.integration.AutomatedTestBase;
import org.apache.sysml.test.integration.TestConfiguration;
import org.apache.sysml.test.utils.TestUtils;
import org.junit.Test;

/**
 * Test FindCorrelation algorithm
 */
public final class FindCorrelationTest extends AutomatedTestBase
{
	private static final Log LOG = LogFactory.getLog(FindCorrelationTest.class.getName());
	private static final String TEST_NAME1 = "FindCorrelationNaive";
	private static final String TEST_NAME2 = "FindCorrelationAdvanced";
	private static final String TEST_DIR = "functions/findcorr/";
	private static final String TEST_CLASS_DIR = TEST_DIR + FindCorrelationTest.class.getSimpleName() + "/";
	
	private static final double eps = Math.pow(10, -10);
	
	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration( TEST_NAME1, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME1, new String[] { "O" }) );
		addTestConfiguration( TEST_NAME2, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME2, new String[] { "O" }) );
	}
	
	@Test
	public void testFindCorrelationCPNaive() {
		testFindCorrelation(TEST_NAME1, ExecType.CP);
	}

	@Test
	public void testFindCorrelationSPNaive() {
		testFindCorrelation(TEST_NAME1, ExecType.SPARK);
	}

	@Test
	public void testFindCorrelationCPAdvanced() {
		testFindCorrelation(TEST_NAME2, ExecType.CP);
	}

	@Test
	public void testFindCorrelationSPAdvanced() {
		testFindCorrelation(TEST_NAME2, ExecType.SPARK);
	}


	private void testFindCorrelation(String testname, ExecType et)
	{	
		RUNTIME_PLATFORM platformOld = rtplatform;
		switch( et ){
			case MR: rtplatform = RUNTIME_PLATFORM.HADOOP; break;
			case SPARK: rtplatform = RUNTIME_PLATFORM.SPARK; break;
			default: rtplatform = RUNTIME_PLATFORM.HYBRID_SPARK; break;
		}
		
		boolean sparkConfigOld = DMLScript.USE_LOCAL_SPARK_CONFIG;
		if( rtplatform == RUNTIME_PLATFORM.SPARK  || rtplatform == RUNTIME_PLATFORM.HYBRID_SPARK )
			DMLScript.USE_LOCAL_SPARK_CONFIG = true;
		
		try
		{
			TestConfiguration config = getTestConfiguration(testname);
			loadTestConfiguration(config);
			
			String HOME = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = HOME + testname + ".dml";
			programArgs = new String[] { "-args", //"-stats", "-explain", "recompile_hops",
					input("k"), input("n"), input("c"), input("alpha"), input("t"), input("A"),
					output("O"), input("n13"), input("n23"), input("alphan23"), input("logn"), input("clogn")}; //
			fullRScriptName = HOME + testname + ".R";
			rCmd = getRCmd(inputDir(), expectedDir());


			final int n = 64;
			final int k = 5; // log n / log log n
			final double rho = 0.4;
			final double c = 2 / (rho*rho);
			final double alpha = 2 / rho;
			final double t = rho/2 * c * Math.log(n) / Math.log(2);

			final double n13 = Math.pow(n,1.0/3), n23 = Math.pow(n,2.0/3), alphan23 = alpha*Math.pow(n, 2.0/3);
			final double logn = Math.log(n)/Math.log(2), clogn = c*Math.log(n)/Math.log(2);
			LOG.info(String.format("\nn: %d\tk: %d\trho: %f\nc: %f\t alpha: %f\tt: %f\nclogn: %f\tn13: %f\talphan23: %f",
					n, k, rho, c, alpha, t, clogn, n13, alphan23));

			final double[][] A = createInput(n, (int)(c*Math.log(n)/Math.log(2)), rho);

			writeInputMatrixWithMTD("k", new double[][]{new double[] {k}}, true);
			writeInputMatrixWithMTD("n", new double[][]{new double[] {n}}, true);
			writeInputMatrixWithMTD("c", new double[][]{new double[] {c}}, true);
			writeInputMatrixWithMTD("alpha", new double[][]{new double[] {alpha}}, true);
			writeInputMatrixWithMTD("t", new double[][]{new double[] {t}}, true);
			writeInputMatrixWithMTD("A", A, true);
			writeInputMatrixWithMTD("n13", new double[][]{new double[] {n13}}, true);
			writeInputMatrixWithMTD("n23", new double[][]{new double[] {n23}}, true);
			writeInputMatrixWithMTD("alphan23", new double[][]{new double[] {alphan23}}, true);
			writeInputMatrixWithMTD("logn", new double[][]{new double[] {logn}}, true);
			writeInputMatrixWithMTD("clogn", new double[][]{new double[] {clogn}}, true);

			//execute tests
			runTest(true, false, null, -1); 
			runRScript(true);
			
			//compare matrices 
			HashMap<MatrixValue.CellIndex, Double> dmlfile = readDMLMatrixFromHDFS("O");
			HashMap<MatrixValue.CellIndex, Double> rfile  = readRMatrixFromFS("O");
			TestUtils.compareMatrices(dmlfile, rfile, eps, "Stat-DML", "Stat-R");
		}
		finally {
			rtplatform = platformOld;
			DMLScript.USE_LOCAL_SPARK_CONFIG = sparkConfigOld;
		}
	}

	private static double[][] createInput(final int n, final int numObs, final double rho) {
		final Random random = new Random(8);

		final int ci, cj;
		{
			final int i0 = random.nextInt(n);
			int j0;
			do {
				j0 = random.nextInt(n);
			} while (i0 == j0);
			ci = Math.min(i0, j0);
			cj = Math.max(i0, j0);
		}
		LOG.info("Correlated pair: ("+(ci+1)+", "+(cj+1)+")");

		final double[][] A = new double[numObs][n]; //getRandomMatrix(nlog, n, 0, 1, 1.0, 7);
		for (int i = 0; i < A.length; i++) {
			A[i] = new double[n];
			for (int j = 0; j < A[i].length; j++) {
				if( j == cj ) {
					A[i][j] = random.nextDouble() < 0.5+rho/2 ? A[i][ci] : -A[i][ci];
				} else
					A[i][j] = random.nextBoolean() ? -1 : 1;
			}
		}

		return A;
	}
}
