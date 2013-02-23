package com.ibm.bi.dml.runtime.controlprogram.parfor.opt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import com.ibm.bi.dml.hops.DataOp;
import com.ibm.bi.dml.hops.Hops;
import com.ibm.bi.dml.hops.LiteralOp;
import com.ibm.bi.dml.lops.LopProperties;
import com.ibm.bi.dml.parser.ForStatement;
import com.ibm.bi.dml.parser.ForStatementBlock;
import com.ibm.bi.dml.parser.FunctionStatement;
import com.ibm.bi.dml.parser.FunctionStatementBlock;
import com.ibm.bi.dml.parser.IfStatement;
import com.ibm.bi.dml.parser.ParForStatement;
import com.ibm.bi.dml.parser.ParForStatementBlock;
import com.ibm.bi.dml.parser.StatementBlock;
import com.ibm.bi.dml.parser.WhileStatement;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.runtime.controlprogram.ForProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.FunctionProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.IfProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.LocalVariableMap;
import com.ibm.bi.dml.runtime.controlprogram.ParForProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.Program;
import com.ibm.bi.dml.runtime.controlprogram.ProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.WhileProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.caching.MatrixObject;
import com.ibm.bi.dml.runtime.controlprogram.parfor.opt.OptNode.ExecType;
import com.ibm.bi.dml.runtime.controlprogram.parfor.opt.OptNode.NodeType;
import com.ibm.bi.dml.runtime.controlprogram.parfor.opt.OptNode.ParamType;
import com.ibm.bi.dml.runtime.controlprogram.parfor.opt.Optimizer.PlanInputType;
import com.ibm.bi.dml.runtime.controlprogram.parfor.opt.PerfTestTool.DataFormat;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.MRJobInstruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.ComputationCPInstruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.Data;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.FunctionCallCPInstruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.RandCPInstruction;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.runtime.matrix.MatrixFormatMetaData;
import com.ibm.bi.dml.runtime.matrix.io.MatrixBlockDSM;
import com.ibm.bi.dml.utils.DMLRuntimeException;
import com.ibm.bi.dml.utils.DMLUnsupportedOperationException;
import com.ibm.bi.dml.utils.HopsException;

/**
 * Converter for creating an internal plan representation for a given runtime program
 * and to modify/create the runtime program according to the optimized plan.
 * 
 * NOTE: currently only one abstract and one runtime plan at a time.
 * This implies that only one parfor optimization can happen at a time.
 */
public class OptTreeConverter 
{		
	//internal configuration flags
	public static boolean INCLUDE_FUNCTIONS = true;
	
	//internal state
	private static OptTreePlanMappingAbstract _hlMap = null; 
	private static OptTreePlanMappingRuntime  _rtMap = null;	
	private static OptNode _tmpParent   = null;
	private static OptNode _tmpChildOld = null;
	private static OptNode _tmpChildNew = null;
	
	static
	{
		_hlMap = new OptTreePlanMappingAbstract();
		_rtMap = new OptTreePlanMappingRuntime();
	}
	
	public static OptTree createOptTree( int ck, double cm, PlanInputType type, ParForStatementBlock pfsb, ParForProgramBlock pfpb ) 
		throws DMLUnsupportedOperationException, DMLRuntimeException, HopsException
	{	
		OptNode root = null;
		switch( type )
		{
			case ABSTRACT_PLAN:
				HashSet<String> memo = new HashSet<String>();
				root = rCreateAbstractOptNode(pfsb, pfpb, pfpb.getVariables(), true, memo);	
				root.checkAndCleanupRecursiveFunc(new HashSet<String>()); //create consistency between recursive info
				root.checkAndCleanupLeafNodes(); //prune unnecessary nodes
				break;
			case RUNTIME_PLAN:
				root = rCreateOptNode( pfpb, pfpb.getVariables(), true, true );
				break;
			default:
				throw new DMLRuntimeException("Optimizer plan input type "+type+" not supported.");
		}
		
		OptTree tree = new OptTree(ck, cm, type, root);
		
		return tree;
	}
	
	/**
	 * 
	 * @param ck
	 * @param cm
	 * @param pfpb
	 * @return
	 * @throws DMLUnsupportedOperationException
	 * @throws DMLRuntimeException
	 */
	public static OptTree createOptTree( int ck, double cm, ParForProgramBlock pfpb ) 
		throws DMLUnsupportedOperationException, DMLRuntimeException
	{
		OptNode root = rCreateOptNode( pfpb, pfpb.getVariables(), true, true );		
		OptTree tree = new OptTree(ck, cm, root);
			
		return tree;
	}
	
	public static OptTree createAbstractOptTree( int ck, double cm, ParForStatementBlock pfsb, ParForProgramBlock pfpb, HashSet<String> memo ) 
		throws DMLUnsupportedOperationException, DMLRuntimeException
	{
		OptTree tree = null;
		OptNode root = null;
		
		try
		{
			root = rCreateAbstractOptNode( pfsb, pfpb, pfpb.getVariables(), true, memo );
			tree = new OptTree(ck, cm, root);
		}
		catch(HopsException he)
		{
			throw new DMLRuntimeException(he);
		}	
			
		return tree;
	}

	/**
	 * 
	 * @param pb
	 * @param vars
	 * @param topLevel
	 * @return
	 * @throws DMLUnsupportedOperationException
	 * @throws DMLRuntimeException
	 */
	public static OptNode rCreateOptNode( ProgramBlock pb, LocalVariableMap vars, boolean topLevel, boolean storeObjs ) 
		throws DMLUnsupportedOperationException, DMLRuntimeException 
	{
		OptNode node = null;
		
		if( pb instanceof IfProgramBlock )
		{
			IfProgramBlock ipb = (IfProgramBlock) pb;
			node = new OptNode( NodeType.IF );
			if(storeObjs)
				_rtMap.putMapping(ipb, node);
			node.setExecType(ExecType.CP);
			//process if condition
			OptNode ifn = new OptNode(NodeType.GENERIC);
			node.addChilds( createOptNodes( ipb.getPredicate(), vars,storeObjs ) );
			node.addChild( ifn );
			for( ProgramBlock lpb : ipb.getChildBlocksIfBody() )
				ifn.addChild( rCreateOptNode(lpb,vars,topLevel, storeObjs) );
			//process else condition
			if( ipb.getChildBlocksElseBody() != null && ipb.getChildBlocksElseBody().size()>0 )
			{
				OptNode efn = new OptNode(NodeType.GENERIC);
				node.addChild( efn );
				for( ProgramBlock lpb : ipb.getChildBlocksElseBody() )
					efn.addChild( rCreateOptNode(lpb,vars,topLevel, storeObjs) );
			}				
		}
		else if( pb instanceof WhileProgramBlock )
		{
			WhileProgramBlock wpb = (WhileProgramBlock) pb;
			node = new OptNode( NodeType.WHILE );
			if(storeObjs)
				_rtMap.putMapping(wpb, node);
			node.setExecType(ExecType.CP);
			//process predicate instruction
			node.addChilds( createOptNodes( wpb.getPredicate(), vars,storeObjs ) );
			//process body
			for( ProgramBlock lpb : wpb.getChildBlocks() )
				node.addChild( rCreateOptNode(lpb,vars,topLevel,storeObjs) );
			
		}
		else if( pb instanceof ForProgramBlock && !(pb instanceof ParForProgramBlock) )
		{
			ForProgramBlock fpb = (ForProgramBlock) pb;
			node = new OptNode( NodeType.FOR );
			if(storeObjs)
				_rtMap.putMapping(fpb, node);
			node.setExecType(ExecType.CP);
			
			//TODO use constant value if known
			node.addParam(ParamType.NUM_ITERATIONS, String.valueOf(CostEstimator.FACTOR_NUM_ITERATIONS));
			
			node.addChilds( createOptNodes( fpb.getFromInstructions(), vars,storeObjs ) );
			node.addChilds( createOptNodes( fpb.getToInstructions(), vars,storeObjs ) );
			node.addChilds( createOptNodes( fpb.getIncrementInstructions(), vars,storeObjs ) );
			
			//process body
			for( ProgramBlock lpb : fpb.getChildBlocks() )
				node.addChild( rCreateOptNode(lpb,vars,topLevel,storeObjs) );
		}
		else if( pb instanceof ParForProgramBlock )
		{
			ParForProgramBlock fpb = (ParForProgramBlock) pb;			
			node = new OptNode( NodeType.PARFOR );
			if(storeObjs)
				_rtMap.putMapping(fpb, node);
			node.setK( fpb.getDegreeOfParallelism() );
			int N = fpb.getNumIterations();
			node.addParam(ParamType.NUM_ITERATIONS, (N!=-1) ? String.valueOf(N) : 
															  String.valueOf(CostEstimatorRuntime.FACTOR_NUM_ITERATIONS));
			
			switch(fpb.getExecMode())
			{
				case LOCAL:
					node.setExecType(ExecType.CP);
					break;
				case REMOTE_MR:
					node.setExecType(ExecType.MR);
					break;
			}
			
			if( !topLevel )
			{
				node.addChilds( createOptNodes( fpb.getFromInstructions(), vars, storeObjs ) );
				node.addChilds( createOptNodes( fpb.getToInstructions(), vars, storeObjs ) );
				node.addChilds( createOptNodes( fpb.getIncrementInstructions(), vars, storeObjs ) );
			}
			
			//process body
			for( ProgramBlock lpb : fpb.getChildBlocks() )
				node.addChild( rCreateOptNode(lpb,vars,false,storeObjs) );			
			
			//parameters, add required parameters
		}
		else //last level program block
		{
			node = new OptNode(NodeType.GENERIC);
			if(storeObjs)
				_rtMap.putMapping(pb, node);
			node.addChilds( createOptNodes(pb.getInstructions(), vars, storeObjs) );
			node.setExecType(ExecType.CP);
		}
			
		return node;
	}
	


	/**
	 * 
	 * @param instset
	 * @param vars
	 * @return
	 * @throws DMLUnsupportedOperationException
	 * @throws DMLRuntimeException
	 */
	public static ArrayList<OptNode> createOptNodes (ArrayList<Instruction> instset, LocalVariableMap vars, boolean storeObjs) 
		throws DMLUnsupportedOperationException, DMLRuntimeException
	{
		ArrayList<OptNode> tmp = new ArrayList<OptNode>(instset.size());
		for( Instruction inst : instset )
			tmp.add( createOptNode(inst,vars,storeObjs) );
		return tmp;
	}
	
	/**
	 * 
	 * @param inst
	 * @param vars
	 * @return
	 * @throws DMLUnsupportedOperationException
	 * @throws DMLRuntimeException
	 */
	public static OptNode createOptNode( Instruction inst, LocalVariableMap vars, boolean storeObjs ) 
		throws DMLUnsupportedOperationException, DMLRuntimeException
	{
		OptNode node = new OptNode(NodeType.INST);
		String instStr = inst.toString();
		String opstr = instStr.split(Instruction.OPERAND_DELIM)[1];
		if(storeObjs)
			_rtMap.putMapping(inst, node);
		node.addParam(ParamType.OPSTRING,opstr);
		
		//exec type
		switch( inst.getType() )
		{
			case CONTROL_PROGRAM:
				node.setExecType(ExecType.CP);
				//exec operations
				//CPInstruction cpinst = (CPInstruction) inst;
				//node.addParam(ParamType.OPTYPE,cpinst.getCPInstructionType().toString());
				break;
			case MAPREDUCE:
			case MAPREDUCE_JOB:
				node.setExecType(ExecType.MR);
				//exec operations
				//MRInstruction mrinst = (MRInstruction) inst;
				//node.addParam(ParamType.OPTYPE,mrinst.getMRInstructionType().toString());
				break;
			default:
				throw new DMLUnsupportedOperationException("Unsupported instruction type.");
		}
		
		//create statistics 
		OptNodeStatistics stats = analyzeStatistics(inst, node, vars);
		node.setStatistics(stats);
		
		return node;
	}
	
	/**
	 * 
	 * @param sb
	 * @param pb
	 * @param vars
	 * @param topLevel
	 * @return
	 * @throws DMLUnsupportedOperationException
	 * @throws DMLRuntimeException
	 * @throws HopsException
	 */
	public static OptNode rCreateAbstractOptNode( StatementBlock sb, ProgramBlock pb, LocalVariableMap vars, boolean topLevel, HashSet<String> memo ) 
		throws DMLUnsupportedOperationException, DMLRuntimeException, HopsException 
	{
		OptNode node = null;
		
		if( pb instanceof IfProgramBlock )
		{
			IfProgramBlock ipb = (IfProgramBlock) pb;
			IfStatement is = (IfStatement) sb.getStatement(0);
			
			node = new OptNode( NodeType.IF );
			_hlMap.putProgMapping(sb, pb, node);
			node.setExecType(ExecType.CP);
			//process if condition
			OptNode ifn = new OptNode(NodeType.GENERIC);
			_hlMap.putProgMapping(sb, pb, ifn);
			ifn.setExecType(ExecType.CP);
			node.addChild( ifn );
			int len = is.getIfBody().size();
			for( int i=0; i<ipb.getChildBlocksIfBody().size() && i<len; i++ )
			{
				ProgramBlock lpb = ipb.getChildBlocksIfBody().get(i);
				StatementBlock lsb = is.getIfBody().get(i);
				ifn.addChild( rCreateAbstractOptNode(lsb,lpb,vars,false, memo) );
			}
			//process else condition
			if( ipb.getChildBlocksElseBody() != null )
			{
				OptNode efn = new OptNode(NodeType.GENERIC);
				_hlMap.putProgMapping(sb, pb, efn);
				efn.setExecType(ExecType.CP);
				node.addChild( efn );
				int len2 = is.getElseBody().size();
				for( int i=0; i<ipb.getChildBlocksElseBody().size() && i<len2; i++ )
				{
					ProgramBlock lpb = ipb.getChildBlocksElseBody().get(i);
					StatementBlock lsb = is.getElseBody().get(i);
					efn.addChild( rCreateAbstractOptNode(lsb,lpb,vars,false, memo) );
				}
			}				
		}
		else if( pb instanceof WhileProgramBlock )
		{
			WhileProgramBlock wpb = (WhileProgramBlock) pb;
			WhileStatement ws = (WhileStatement) sb.getStatement(0);
			
			node = new OptNode( NodeType.WHILE );
			_hlMap.putProgMapping(sb, pb, node);
			node.setExecType(ExecType.CP);
			//process body
			int len = ws.getBody().size();
			for( int i=0; i<wpb.getChildBlocks().size() && i<len; i++ )
			{
				ProgramBlock lpb = wpb.getChildBlocks().get(i);
				StatementBlock lsb = ws.getBody().get(i);
				node.addChild( rCreateAbstractOptNode(lsb,lpb,vars,false, memo) );
			}			
		}
		else if( pb instanceof ForProgramBlock && !(pb instanceof ParForProgramBlock) )
		{
			ForProgramBlock fpb = (ForProgramBlock) pb;
			ForStatementBlock fsb = (ForStatementBlock)sb;
			ForStatement fs = (ForStatement) fsb.getStatement(0);
			
			node = new OptNode( NodeType.FOR );
			_hlMap.putProgMapping(sb, pb, node);
			node.setExecType(ExecType.CP);
			
			node.addParam(ParamType.NUM_ITERATIONS, String.valueOf(CostEstimator.FACTOR_NUM_ITERATIONS));
			
			node.addChilds( rCreateAbstractOptNodes( fsb.getFromHops(), vars, memo ) );
			node.addChilds( rCreateAbstractOptNodes( fsb.getToHops(), vars, memo ) );
			node.addChilds( rCreateAbstractOptNodes( fsb.getIncrementHops(), vars, memo ) );
			
			//process body
			int len = fs.getBody().size();
			for( int i=0; i<fpb.getChildBlocks().size() && i<len; i++ )
			{
				ProgramBlock lpb = fpb.getChildBlocks().get(i);
				StatementBlock lsb = fs.getBody().get(i);
				node.addChild( rCreateAbstractOptNode(lsb,lpb,vars,false, memo) );
			}	
		}
		else if( pb instanceof ParForProgramBlock )
		{
			ParForProgramBlock fpb = (ParForProgramBlock) pb;		
			ParForStatementBlock fsb = (ParForStatementBlock)sb;
			ParForStatement fs = (ParForStatement) fsb.getStatement(0);
			node = new OptNode( NodeType.PARFOR );
			_hlMap.putProgMapping(sb, pb, node);
			node.setK( fpb.getDegreeOfParallelism() );
			int N = fpb.getNumIterations();
			node.addParam(ParamType.NUM_ITERATIONS, (N!=-1) ? String.valueOf(N) : 
															  String.valueOf(CostEstimator.FACTOR_NUM_ITERATIONS));
			
			switch(fpb.getExecMode())
			{
				case LOCAL:
					node.setExecType(ExecType.CP);
					break;
				case REMOTE_MR:
					node.setExecType(ExecType.MR);
					break;
			}
			
			if( !topLevel )
			{
				node.addChilds( rCreateAbstractOptNodes( fsb.getFromHops(), vars, memo ) );
				node.addChilds( rCreateAbstractOptNodes( fsb.getToHops(), vars, memo ) );
				node.addChilds( rCreateAbstractOptNodes( fsb.getIncrementHops(), vars, memo ) );
			}
			
			//process body
			int len = fs.getBody().size();
			for( int i=0; i<fpb.getChildBlocks().size() && i<len; i++ )
			{
				ProgramBlock lpb = fpb.getChildBlocks().get(i);
				StatementBlock lsb = fs.getBody().get(i);
				node.addChild( rCreateAbstractOptNode(lsb,lpb,vars,false, memo) );
			}
			
			//parameters, add required parameters
			HashMap<String,String> lparams = fpb.getParForParams();
			node.addParam(ParamType.DATA_PARTITIONER, lparams.get(ParForStatementBlock.DATA_PARTITIONER));
			node.addParam(ParamType.TASK_PARTITIONER, lparams.get(ParForStatementBlock.TASK_PARTITIONER));
			node.addParam(ParamType.RESULT_MERGE, lparams.get(ParForStatementBlock.RESULT_MERGE));
		}
		else //last level program block
		{
			//process all hops
			node = new OptNode(NodeType.GENERIC);
			_hlMap.putProgMapping(sb, pb, node);
			node.addChilds( createAbstractOptNodes(sb.get_hops(), vars, memo) );
			node.setExecType(ExecType.CP);
			
			//TODO remove this workaround once this information can be obtained from hops/lops compiler
			if( node.isCPOnly() && containsMRJobInstruction(pb) )
				node.setExecType(ExecType.MR);
				
			//TODO remove this workaround once this information can be obtained from hops/lops omcpiler
			//process function call statements
			if( INCLUDE_FUNCTIONS )
			{
				for( Instruction inst : pb.getInstructions() )
					if( inst instanceof FunctionCallCPInstruction )
					{
						FunctionCallCPInstruction fci = (FunctionCallCPInstruction) inst;
						String fname = fci.getFunctionName();
						String fnspace = fci.getNamespace();
						String fKey = fnspace + Program.KEY_DELIM + fname; 
						FunctionProgramBlock fpb = pb.getProgram().getFunctionProgramBlock(fnspace, fname);
						FunctionStatementBlock fsb = sb.getDMLProg().getFunctionStatementBlock(fnspace, fname);
						FunctionStatement fs = (FunctionStatement) fsb.getStatement(0);
						
						OptNode fn = new OptNode(NodeType.FUNCCALL);
						_hlMap.putProgMapping(fsb, fpb, fn);
						fn.setExecType(ExecType.CP);
						fn.addParam(ParamType.OPSTRING, fKey);
						node.addChild( fn );
						
						//process body; NOTE: memo prevents inclusion of functions multiple times
						if( !memo.contains(fKey) )
						{
							memo.add(fKey); 
						
							int len = fs.getBody().size();
							for( int i=0; i<fpb.getChildBlocks().size() && i<len; i++ )
							{
								ProgramBlock lpb = fpb.getChildBlocks().get(i);
								StatementBlock lsb = fs.getBody().get(i);
								fn.addChild( rCreateAbstractOptNode(lsb,lpb,vars,false, memo) );
							}
						
							memo.remove(fKey);							
						}
						else
							fn.addParam(ParamType.RECURSIVE_CALL, "true");
					}
			}
		}
		
		//final cleanup
		node.checkAndCleanupLeafNodes(); //NOTE: required because this function is also used to create subtrees
		
		return node;
	}
	
	//TODO predicate hops e.g., at whilestatementblock


	/**
	 * 
	 * @param hops
	 * @param vars
	 * @return
	 */
	public static ArrayList<OptNode> createAbstractOptNodes(ArrayList<Hops> hops, LocalVariableMap vars, HashSet<String> memo ) 
	{
		ArrayList<OptNode> ret = new ArrayList<OptNode>(); 
		for( Hops hop : hops )
			ret.addAll(rCreateAbstractOptNodes(hop,vars, memo));
		return ret;
	}
	
	/**
	 * 
	 * @param hop
	 * @param vars
	 * @return
	 */
	public static ArrayList<OptNode> rCreateAbstractOptNodes(Hops hop, LocalVariableMap vars, HashSet<String> memo) 
	{
		ArrayList<OptNode> ret = new ArrayList<OptNode>(); 
		ArrayList<Hops> in = hop.getInput();
		
		if( !(hop instanceof DataOp || hop instanceof LiteralOp) )
		{
			OptNode node = new OptNode(NodeType.HOP);
			String opstr = hop.getOpString();
			node.addParam(ParamType.OPSTRING,opstr);
			LopProperties.ExecType et = hop.getExecType();
			node.setExecType((et==LopProperties.ExecType.MR) ? ExecType.MR : ExecType.CP); //note: for scalars no exec type
			_hlMap.putHopMapping(hop, node);
			ret.add(node);
		}	

		if( in != null )
			for( Hops hin : in ) 
				if( !(hin instanceof DataOp || hin instanceof LiteralOp ) ) //no need for opt nodes
					ret.addAll(rCreateAbstractOptNodes(hin,vars, memo));

		return ret;
	}

	/**
	 * 
	 * @param pb
	 * @return
	 */
	private static boolean containsMRJobInstruction( ProgramBlock pb )
	{
		boolean ret = false;
		for( Instruction inst : pb.getInstructions() )
			if( inst instanceof MRJobInstruction )
			{
				ret = true;
				break;
			}

		return ret;
	}
	
	
	/**
	 * 
	 * @param inst
	 * @param on
	 * @param vars
	 * @return
	 * @throws DMLRuntimeException
	 */
	private static OptNodeStatistics analyzeStatistics(Instruction inst, OptNode on, LocalVariableMap vars) 
		throws DMLRuntimeException 
	{
		OptNodeStatistics ret = null;
		String instName = on.getInstructionName();
		
		if( PerfTestTool.isRegisteredInstruction(instName) )
		{	
			if( inst instanceof RandCPInstruction )
			{
				RandCPInstruction linst = (RandCPInstruction) inst;
				DataFormat df = (   linst.sparsity >= MatrixBlockDSM.SPARCITY_TURN_POINT 
						         || linst.cols <= MatrixBlockDSM.SKINNY_MATRIX_TURN_POINT ) ? 
						            DataFormat.DENSE : DataFormat.SPARSE; 
				ret = new OptNodeStatistics(linst.rows, linst.cols, -1, -1, linst.sparsity, df);
			}
			else if ( inst instanceof FunctionCallCPInstruction )
			{
				FunctionCallCPInstruction linst = (FunctionCallCPInstruction)inst;
				ArrayList<String> params = linst.getBoundInputParamNames();
				ret = new OptNodeStatistics(); //default vals
				
				double maxSize = 0;
				for( String param : params ) //use the largest input matrix
				{
					Data dat = vars.get(param);
					if( dat!=null && dat.getDataType()==DataType.MATRIX )
					{
						MatrixObject mdat1 = (MatrixObject) dat;
						MatrixCharacteristics mc1 = ((MatrixFormatMetaData)mdat1.getMetaData()).getMatrixCharacteristics();
						
						if( mc1.numRows*mc1.numColumns > maxSize )
						{
							ret.setDim1( mc1.numRows );
							ret.setDim2( mc1.numColumns );
							ret.setSparsity( mc1.nonZero /(  ret.getDim1() * ret.getDim2() ) ); //sparsity
							ret.setDataFormat(  (ret.getSparsity() < MatrixBlockDSM.SPARCITY_TURN_POINT 
									          && mc1.numColumns > MatrixBlockDSM.SKINNY_MATRIX_TURN_POINT ) ? 
									        		  DataFormat.SPARSE : DataFormat.DENSE ); 
							maxSize = mc1.numRows*mc1.numColumns;
						}
					}
				}
			}
			else if ( inst instanceof ComputationCPInstruction ) //needs to be last CP case
			{
				//AggregateBinaryCPInstruction, AggregateUnaryCPInstruction, 
				//FunctionCallCPInstruction, ReorgCPInstruction
				
				ComputationCPInstruction linst = (ComputationCPInstruction) inst;
				ret = new OptNodeStatistics(); //default
				
				if( linst.input1 != null && linst.input2 != null ) //binary
				{
					Data dat1 = vars.get( linst.input1.get_name() );
					Data dat2 = vars.get( linst.input2.get_name() );
					
					if( dat1 != null )
					{
						MatrixObject mdat1 = (MatrixObject) dat1;
						MatrixCharacteristics mc1 = ((MatrixFormatMetaData)mdat1.getMetaData()).getMatrixCharacteristics();
						ret.setDim1( mc1.numRows );
						ret.setDim2( mc1.numColumns );
						ret.setSparsity( mc1.nonZero /( ret.getDim1() * ret.getDim2() ) ); //sparsity
						ret.setDataFormat((ret.getSparsity() < MatrixBlockDSM.SPARCITY_TURN_POINT )? DataFormat.SPARSE : DataFormat.DENSE); 
					}
					if( dat2 != null )
					{
						MatrixObject mdat2 = (MatrixObject) dat2;
						MatrixCharacteristics mc2 = ((MatrixFormatMetaData)mdat2.getMetaData()).getMatrixCharacteristics();
						ret.setDim3( mc2.numRows );
						ret.setDim4( mc2.numColumns );
						ret.setDataFormat( (ret.getSparsity() < MatrixBlockDSM.SPARCITY_TURN_POINT ) ? DataFormat.SPARSE : DataFormat.DENSE ); 
					}
				}
				else //unary
				{
					Data dat1 = vars.get( linst.input1.get_name() );
					
					if( dat1 != null )
					{
						MatrixObject mdat1 = (MatrixObject) dat1;
						MatrixCharacteristics mc1 = ((MatrixFormatMetaData)mdat1.getMetaData()).getMatrixCharacteristics();
						ret.setDim1( mc1.numRows );
						ret.setDim2( mc1.numColumns );
						ret.setSparsity( mc1.nonZero /( ret.getDim1() * ret.getDim2() ) ); //sparsity
						ret.setDataFormat((ret.getSparsity() < MatrixBlockDSM.SPARCITY_TURN_POINT ) ? DataFormat.SPARSE : DataFormat.DENSE); 
					}					
				}
			}
		}
		
		if( ret == null )
			ret = new OptNodeStatistics(); //default values
		
		return ret; //null if not reqistered for profiling
	}

	/**
	 * 
	 * @param parent
	 * @param n
	 * @param pbOld
	 * @param pbNew
	 * @throws DMLUnsupportedOperationException
	 */
	public static void replaceProgramBlock(OptNode parent, OptNode n, ProgramBlock pbOld, ProgramBlock pbNew, boolean rtMap) 
		throws DMLUnsupportedOperationException
	{
		ProgramBlock pbParent = null;
		if( rtMap )
			pbParent = (ProgramBlock)_rtMap.getMappedObject( parent.getID() );
		else
			pbParent = (ProgramBlock)_hlMap.getMappedProg( parent.getID() )[1];
		
		if( pbParent instanceof IfProgramBlock )
		{
			IfProgramBlock ipb = (IfProgramBlock) pbParent;
			replaceProgramBlock( ipb.getChildBlocksIfBody(), pbOld, pbNew );
			replaceProgramBlock( ipb.getChildBlocksElseBody(), pbOld, pbNew );				
		}
		else if( pbParent instanceof WhileProgramBlock )
		{
			WhileProgramBlock wpb = (WhileProgramBlock) pbParent;
			replaceProgramBlock( wpb.getChildBlocks(), pbOld, pbNew );			
		}
		else if( pbParent instanceof ForProgramBlock || pbParent instanceof ParForProgramBlock )
		{
			ForProgramBlock fpb = (ForProgramBlock) pbParent;
			replaceProgramBlock( fpb.getChildBlocks(), pbOld, pbNew );	
		}
		else if( pbParent instanceof FunctionProgramBlock )
		{
			FunctionProgramBlock fpb = (FunctionProgramBlock) pbParent;
			replaceProgramBlock( fpb.getChildBlocks(), pbOld, pbNew );	
		}
		else
			throw new DMLUnsupportedOperationException("Optimizer doesn't support "+pbParent.getClass().getName());
		
		//update repository
		if( rtMap )
			_rtMap.replaceMapping(pbNew, n);
		else
			_hlMap.replaceMapping(pbNew, n);
	}
	
	/**
	 * 
	 * @param pbs
	 * @param pbOld
	 * @param pbNew
	 */
	public static void replaceProgramBlock(ArrayList<ProgramBlock> pbs, ProgramBlock pbOld, ProgramBlock pbNew)
	{
		int len = pbs.size();
		for( int i=0; i<len; i++ )
			if( pbs.get(i) == pbOld )
				pbs.set(i, pbNew);
	}


	
	///////////////////////////////
	//                           //
	// internal state management //
	//                           //
	///////////////////////////////
	

	public static OptTreePlanMappingAbstract getAbstractPlanMapping()
	{
		return _hlMap;
	}
	
	public static OptTreePlanMappingRuntime getRuntimePlanMapping()
	{
		return _rtMap;
	}
	
	/**
	 * 
	 * @param pRoot
	 * @param hlNodeID
	 * @param newRtNode
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static OptNode exchangeTemporary(OptNode pRoot, long hlNodeID, OptNode newRtNode) 
		throws DMLRuntimeException 
	{
		OptNode hlNode = _hlMap.getOptNode(hlNodeID);
		if( hlNode.getNodeType() == NodeType.PARFOR )
		{
			ParForProgramBlock pb = (ParForProgramBlock) _hlMap.getMappedProg(hlNodeID)[1];
			OptNode rtNode = _rtMap.getOptNode(pb);
			
			//copy node internals (because it might be root node)
			_tmpChildOld = rtNode.createShallowClone();
			rtNode.setExecType(newRtNode.getExecType()); //TODO extend as required
		}
		else if (hlNode.getNodeType() == NodeType.HOP)
		{
			long pid1 = _hlMap.getMappedParentID(hlNode.getID()); //pbID
			ProgramBlock pb = (ProgramBlock) _hlMap.getMappedProg(pid1)[1];
			OptNode rtNode1 = _rtMap.getOptNode(pb);
			long pid2 = _rtMap.getMappedParentID(rtNode1.getID());
			OptNode rtNode2 = _rtMap.getOptNode(pid2);
			
			_tmpParent = rtNode2;
			_tmpChildOld = rtNode1;		
			_tmpChildNew = newRtNode;
			_tmpParent.exchangeChild(_tmpChildOld, _tmpChildNew);
		}
		else
		{
			throw new DMLRuntimeException("Unexpected node type for plan node exchange.");
		}
		
		return pRoot;
	}
	
	/**
	 * 
	 * @param hlNodeID
	 * @throws DMLRuntimeException
	 */
	public static void revertTemporaryChange( long hlNodeID ) 
		throws DMLRuntimeException 
	{
		OptNode node = _hlMap.getOptNode(hlNodeID);
		
		if( node.getNodeType() == NodeType.PARFOR )
		{
			ParForProgramBlock pb = (ParForProgramBlock) _hlMap.getMappedProg(hlNodeID)[1];
			OptNode rtNode = _rtMap.getOptNode(pb);
			rtNode.setExecType(_tmpChildOld.getExecType()); 	
		}
		else if( node.getNodeType() == NodeType.HOP )
		{
			//revert change (overwrite tmp child)
			_tmpParent.exchangeChild(_tmpChildNew,_tmpChildOld);	
		}
		else
		{
			throw new DMLRuntimeException("Unexpected node type for plan node exchange.");
		}
		
		//cleanup
		_tmpParent = null;
		_tmpChildOld = null;
	}

	/**
	 * 
	 * @param pRoot
	 * @param hlNodeID
	 * @param newRtNode
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static OptNode exchangePermanently(OptNode pRoot, long hlNodeID, OptNode newRtNode) 
		throws DMLRuntimeException 
	{
		OptNode hlNode = _hlMap.getOptNode(hlNodeID);
		if( hlNode.getNodeType() == NodeType.PARFOR )
		{
			ParForProgramBlock pb = (ParForProgramBlock) _hlMap.getMappedProg(hlNodeID)[1];
			OptNode rtNode = _rtMap.getOptNode(pb);
			
			//copy node internals (because it might be root node)
			//(no need for update mapping)
			rtNode.setExecType(newRtNode.getExecType()); //
		}
		else if (hlNode.getNodeType() == NodeType.HOP)
		{
			long pid1 = _hlMap.getMappedParentID(hlNode.getID()); //pbID
			ProgramBlock pb = (ProgramBlock) _hlMap.getMappedProg(pid1)[1];
			OptNode rtNode1 = _rtMap.getOptNode(pb);
			long pid2 = _rtMap.getMappedParentID(rtNode1.getID());
			OptNode rtNode2 = _rtMap.getOptNode(pid2);
			
			rtNode2.exchangeChild(rtNode1, newRtNode);
			
			//finally update mapping (all internal repositories)
			newRtNode.setID(rtNode1.getID());
			_rtMap.replaceMapping(pb, newRtNode);
		}
		else
		{
			throw new DMLRuntimeException("Unexpected node type for plan node exchange.");
		}
		
		return pRoot;
	}

	public static void clear()
	{
		if( _hlMap != null )
			_hlMap.clear();
		if( _rtMap != null )
			_rtMap.clear();
		
		_tmpParent = null;
		_tmpChildOld = null;
		_tmpChildNew = null;
	}

}
