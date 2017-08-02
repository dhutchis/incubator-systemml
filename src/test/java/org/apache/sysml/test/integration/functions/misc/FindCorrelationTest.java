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

package org.apache.sysml.test.integration.functions.misc;

import java.util.HashMap;

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
public class FindCorrelationTest extends AutomatedTestBase
{
	private static final String TEST_NAME1 = "FindCorrelationNaive";
	private static final String TEST_NAME2 = "FindCorrelationAdvanced";
	private static final String TEST_DIR = "functions/misc/";
	private static final String TEST_CLASS_DIR = TEST_DIR + FindCorrelationTest.class.getSimpleName() + "/";
	
//	private static final int rows = 123;
//	private static final int cols = 321;
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
		if( rtplatform == RUNTIME_PLATFORM.SPARK )
			DMLScript.USE_LOCAL_SPARK_CONFIG = true;
		
		try
		{
			TestConfiguration config = getTestConfiguration(testname);
			loadTestConfiguration(config);
			
			String HOME = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = HOME + testname + ".dml";
			programArgs = new String[] { "-explain", "hops", "-stats", "-args", input("A"), output("O")};
//			fullRScriptName = HOME + testname + ".R";
			rCmd = getRCmd(inputDir(), expectedDir());

			int n = 8;
			int nlog = (int)(Math.log(n)/Math.log(2)+1);

			double[][] A = getRandomMatrix(nlog, n, 0, 1, 1.0, 7);
			for (int i = 0; i < A.length; i++) {
				for (int j = 0; j < A[i].length; j++) {
					A[i][j] = A[i][j] <= 0.5 ? -1 : 1;
				}
			}
//			double[][] Y = getRandomMatrix(rows, cols, -1, 1, Ysparsity, 3);
			writeInputMatrixWithMTD("A", A, true);
//			writeInputMatrixWithMTD("Y", Y, true);

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
}
