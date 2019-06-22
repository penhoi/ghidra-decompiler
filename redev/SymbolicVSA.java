
/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
//Creates a selection in the current program consisting of the sum
//of all function bodies.
//@category Selection

import java.io.IOException;
import java.util.*; // Map & List

import java.lang.Math;
import java.lang.Object;
import java.text.DecimalFormat;

import ghidra.program.model.listing.*;
import ghidra.program.model.block.*; //CodeBlock && CodeBlockImpl
import ghidra.program.model.address.*;
import ghidra.program.model.lang.OperandType;
import ghidra.program.model.lang.Register;
import ghidra.program.model.scalar.Scalar;

import ghidra.program.model.mem.*;

import ghidra.program.database.*;
import ghidra.program.database.function.*;
import ghidra.program.database.code.*;

import ghidra.util.task.TaskMonitor; // TaskMonitor
import ghidra.app.script.GhidraScript;

public class SymbolicVSA extends GhidraScript {
    private Program program;
    private Listing listing;

    /* Calculate the address space of code segment */
    AddressSet getCodeSegmentAddresRange() {
        MemoryBlock[] blocks;
        Address start, end;
        long startVM, endVM;
        Address startF = null, endF = null;

        blocks = program.getMemory().getBlocks();
        startVM = 10; // startVM = (unsigned long) -1
        endVM = 0;

        for (MemoryBlock blk : blocks) {
            /*
             * An ELF file always has several code sections. If yes, we assume they are
             * layed continuously
             */
            if (!(blk.isExecute() && blk.isInitialized() && blk.isLoaded()))
                continue;

            start = blk.getStart();
            end = blk.getEnd();

            if (startVM > endVM) { // This means we find the first code section
                startVM = start.getOffset();
                endVM = end.getOffset();
                startF = start;
                continue;
            }

            /* considering code alignment, default to 16 bytes */
            if (endVM < end.getOffset() && start.getOffset() <= (endVM + 15 >> 4 << 4)) {
                endVM = end.getOffset();
                endF = end;
            } else {
                /* warning ? */
                println(String.format("87: Non-continuous section: %s: 0x%x - 0x%x", blk.getName(), start.getOffset(),
                        end.getOffset()));
            }
        }

        if (startF == null || endF == null) {
            throw new IllegalArgumentException("Faile to find code segment");
        }
        return new AddressSet(startF, endF);
    }

    @Override
    public void run() {
        program = state.getCurrentProgram();
        listing = program.getListing();

        /* travese all functions */
        AddressSet codesegRng = getCodeSegmentAddresRange();
        Address startVM = codesegRng.getMinAddress();
        Address endVM = codesegRng.getMaxAddress();

        FunctionIterator iter = listing.getFunctions(true);

        while (iter.hasNext() && !monitor.isCancelled()) {
            Function f = iter.next();
            String fname = f.getName();
            Address f_startVM, f_endVM;

            f_startVM = f.getBody().getMinAddress();
            f_endVM = f.getBody().getMaxAddress();

            /* skip all functions out the address space of current segment */
            if (f_startVM.getOffset() < startVM.getOffset() || f_endVM.getOffset() > endVM.getOffset())
                continue;

            // Entry-point
            if (f.getEntryPoint().getOffset() != 0x400546)
                continue;

            println("Function Entry: " + f.getEntryPoint());
            println("Function Name: " + f.getName());

            FunctionSMAR smar = new FunctionSMAR(program, listing, f, monitor);
            smar.doSMARecording();

            Map<Long, Map<String, Set<String>>> smart = smar.getSMARTable();

            println(smart.toString());
        }
    }

    boolean mergeVSATables() {
        // for (SMARBlock blk: m_blocks) {
        // Map<String, Set<String>> table = blk.getVSATable();

        /* merge two tables */
        // }
        return true;
    }

    boolean structAnalysis() {
        return true;
    }
}

/*----------------------------copy from FunctionSMAR.java-------------------------------------------------------------------*/
/*
 * Function-level symbolic memory access recording (SMAR) Every symbolic value
 * defines a domain
 */
class FunctionSMAR {
    private final Program m_program;
    private final Listing m_listDB;
    private final Function m_function;
    private TaskMonitor m_monitor;

    private Map<Address, ExecutionBlock> m_blocks; // All blocks in this function

    public FunctionSMAR(Program program, Listing listintDB, Function function, TaskMonitor monitor) {
        m_program = program;
        m_listDB = listintDB;
        m_function = function;
        m_monitor = monitor;

        constructCFG();
    }

    /**
     * Construct the CFG for all basic blocks
     */
    private void constructCFG() {
        if (m_blocks == null)
            m_blocks = new HashMap<>(); // Basic Blocks of this function

        try {
            /* Create ExecutionBlock for each Ghidra's codeblock */
            CodeBlockModel blkModel = new BasicBlockModel(m_program);
            AddressSetView addrSV = m_function.getBody();
            CodeBlockIterator codeblkIt = blkModel.getCodeBlocksContaining(addrSV, m_monitor);

            while (codeblkIt.hasNext()) {
                CodeBlock codeBlk = codeblkIt.next();
                ExecutionBlock smarBlk = new ExecutionBlock(m_listDB, m_function, codeBlk);
                Address addrStart = codeBlk.getFirstStartAddress();
                m_blocks.put(addrStart, smarBlk);
                System.out.println("178: add smart block : " + smarBlk.toString());
            }
        } catch (Exception e) {
            /* fixe-me: ignore current function */
            System.err.println("Failed to obtain Ghidra's basic blocks @ " + m_function.getName());
        }

        try {
            /* Create control-flow graph */
            for (ExecutionBlock curSMARBlk : m_blocks.values()) {
                /* find the next-blocks of current code-block */
                Set<ExecutionBlock> nxtSMARblks = new HashSet<>();
                CodeBlock curCodeBlk = curSMARBlk.getCodeBlock();
                CodeBlockReferenceIterator di = curCodeBlk.getDestinations(m_monitor);
                while (di.hasNext()) {
                    CodeBlockReference ref = di.next();
                    CodeBlock nxtCodeBlk = ref.getDestinationBlock();
                    Address addrStart = nxtCodeBlk.getFirstStartAddress();
                    ExecutionBlock nxtSMARBlk = m_blocks.get(addrStart);
                    if (nxtSMARBlk != null) {
                        nxtSMARblks.add(nxtSMARBlk);
                    }
                }

                /* set the m_next filed of current SMARTblock */
                curSMARBlk.setSuccessor(nxtSMARblks);
            }
        } catch (Exception e) {
            /* fixe-me: ignore current function */
            System.err.println("Failed to contruct the CFG for function " + m_function.getName());
        }
    }

    /**
     * Do symbolic memory access recording for current function. Apply the VSA
     * algorithm.
     * 
     * @return
     */
    public boolean doSMARecording() {
        /* Obtain the wrapper object for GHIDRA's basic block */
        Address fentry = m_function.getEntryPoint();
        ExecutionBlock firstBlk = m_blocks.get(fentry);
        System.out.println(m_blocks.toString());
        System.out.println(fentry.toString());
        assert (firstBlk != null);

        /* Initialize the Machine state */
        X86Interpreter inpt = X86Interpreter.getInterpreter();
        MachineState init_state = MachineState.createInitState(inpt.getCPU());
        firstBlk.setInitMachState(init_state);

        try {
            /* loop until no changes to symbolic state */
            ExecutionBlock smarBlk;
            while (true) {
                /* pick up a block which has Machine-state to run? */
                smarBlk = null;
                for (ExecutionBlock blk : m_blocks.values()) {
                    int nState = blk.getNumOfMachState();
                    boolean bDirty = blk.isSMRTDirty();

                    if (nState > 0 && bDirty) {
                        smarBlk = blk;
                        break;
                    }
                }

                /* end loop 8 */
                if (smarBlk == null)
                    break;

                /* smarBlk != null */
                traverseBlocksOnce(smarBlk);
            }
        } catch (Exception e) {
            /* fixe-me: ignore current function */
            System.out.println("272: Failed to traversBlocks: " + e.toString());
        }
        return true;
    }

    /**
     * traverse all code-blocks recusively in depth-first search (DFS) order
     * 
     * @param start_block: The block for starting traversing
     * @return
     */
    private boolean traverseBlocksOnce(ExecutionBlock start_block) {
        /* set all blocks un-visted */
        for (ExecutionBlock blk : m_blocks.values()) {
            blk.m_bVisted = false;
        }

        start_block.runCFGOnce();
        return true;
    }

    /**
     * Fetch SMART from each SMARBlock.
     * 
     * @return : the SMAR-table
     */
    public Map<Long, Map<String, Set<String>>> getSMARTable() {
        SMARTable SMARTable = new SMARTable(); // Symbolic Store

        /* fetch SMART from each block */
        Map<Long, Map<String, Set<String>>> smart;

        for (ExecutionBlock blk : m_blocks.values()) {
            smart = blk.getSMARTable();

            if (smart != null)
                SMARTable.putAll(smart);
        }
        return SMARTable.m_tbl;
    }
}

/*
 * Basic block Representation for a given function, a wrapper of Ghidra's basic
 * block
 */
class SMARBlock {
    private Listing m_listDB;
    private CodeBlock m_block; // Ghidra's basic block

    private AddressSet m_addrSet; // The address space convering this block

    public boolean m_dirtySMART; // The SMRT table is diry, means current block needs a new round of recording if
                                 // also have MachineState

    X86Interpreter m_inpt;

    /*
     * Each basic block has its own SMARTable, used for storing memory access record
     */
    SMARTable m_smarTable;

    public SMARBlock(Listing listintDB, CodeBlock ghidra_block, AddressSet addrSet) {

        m_listDB = listintDB;
        m_block = ghidra_block;
        m_addrSet = addrSet;

        m_dirtySMART = true; // Set it do dirty at the first time

        m_inpt = X86Interpreter.getInterpreter();

        /* Each basic block has its own SMARTable */
        m_smarTable = new SMARTable();
    }

    public CodeBlock getCodeBlock() {
        return m_block;
    }

    boolean isDirty() {
        return m_dirtySMART;
    }

    public Map<Long, Map<String, Set<String>>> getSMARTable() {
        return m_smarTable.m_tbl;
    }

    public void doRecording(MachineState state) {
        /* iterate every instruction in this block */
        InstructionIterator iiter = m_listDB.getInstructions(m_addrSet, true);
        SMARTable smart = new SMARTable();

        while (iiter.hasNext()) {
            Instruction inst = iiter.next();
            boolean suc = m_inpt.doRecording(state, smart, inst);
        }

        if (m_smarTable.containsAll(smart)) {
            System.out.println("593: YES");
            m_dirtySMART = false;
        } else {
            m_smarTable.putAll(smart);
            m_dirtySMART = true;
            System.out.println("598: False");
        }
    }
}

class ExecutionBlock {
    private SMARBlock m_block;
    ExecutionBlock m_truecondBranch; // For conditional jumps, this node would be the jump target.
    ExecutionBlock m_falldownBranch;
    Set<ExecutionBlock> m_successor; // A set of successors

    private Set<MachineState> m_MachState;

    public boolean m_bVisted; // Visted in current cycle

    ExecutionBlock(Listing listintDB, Function function, CodeBlock ghidra_block) {
        AddressSet addrSet = ghidra_block.intersect(function.getBody());

        m_block = new SMARBlock(listintDB, ghidra_block, addrSet);
        m_MachState = new HashSet<>();
        m_bVisted = false;
    }

    public void setSuccessor(Set<ExecutionBlock> succsor) {
        m_successor = succsor;
    }

    public void setInitMachState(MachineState init_state) {
        if (m_MachState == null) {
            m_MachState = new HashSet<>();
        }

        m_MachState.add(init_state);
    }

    private void addMachState(MachineState new_state) {
        m_MachState.add(new_state);
    }

    public int getNumOfMachState() {
        if (m_MachState == null)
            return 0;
        else
            return m_MachState.size();
    }

    public CodeBlock getCodeBlock() {
        return m_block.getCodeBlock();
    }

    public boolean isSMRTDirty() {
        return m_block.isDirty();
    }

    public Map<Long, Map<String, Set<String>>> getSMARTable() {
        return m_block.getSMARTable();
    }

    public void runCFGOnce() {
        /*
         * Recording memory access at the start of the current code block, in DFS order
         */
        Set<MachineState> selfloopMachState = null; // A block may loop itself. If yes, we store a copy of MachineState
                                                    // for it

        m_bVisted = true; // Current block is already visted, so no need to traverse again at current
                          // cycle */

        /* Set the CPU state for each successor */
        for (Iterator<MachineState> itor = m_MachState.iterator(); itor.hasNext();) {
            MachineState mstate = itor.next();

            m_block.doRecording(mstate);

            /* Set the CPU state for each successor */
            int cntNxt = m_successor.size();
            for (ExecutionBlock nextBlk : m_successor) {
                cntNxt--;

                /* self-loop ? */
                if (nextBlk == this) {
                    /* If there is a self-loop, copy the CPU state for next traversing cycle */
                    if (selfloopMachState == null) {
                        selfloopMachState = new HashSet<>();
                    }
                    MachineState s = mstate.forkState();
                    selfloopMachState.add(s);
                    continue;
                }

                /* fork register status if there are more than 2 successors */
                if (cntNxt > 0) {
                    MachineState s = mstate.forkState();
                    nextBlk.addMachState(s);
                } else {
                    nextBlk.addMachState(mstate);
                }
            }

            /* use itor.remove() instead of Set.remove() */
            itor.remove();
        }

        /* All MachineState have been consumed */
        assert (m_MachState.size() == 0);

        if (selfloopMachState != null) {
            m_MachState = selfloopMachState;
        }

        /* traverse all outgoing edges in this block */
        for (ExecutionBlock nextBlk : m_successor) {
            if (!nextBlk.m_bVisted && nextBlk.isSMRTDirty())
                nextBlk.runCFGOnce();
        }
    }

}

/*----------------------------copy from MachineState.java-------------------------------------------------------------------*/
/*
 * Machine state: A simple machine mode consist with only registers and memory
 */
class MachineState {
    private Map<String, String> m_regs;
    private Map<String, String> m_mems;

    public MachineState(Map<String, String> register_status, Map<String, String> memory_status) {
        m_regs = register_status;
        m_mems = memory_status;
    }

    /* Used for forking */
    private MachineState() {

    }

    public static MachineState createInitState(X86Processor cpu) {
        MachineState s = new MachineState();

        /* Set register values to symbolic initial values */
        s.m_regs = new HashMap<>(); // CPU State : Registers
        s.m_mems = new HashMap<>(); // CPU State : Memory slot

        String[] allRegs = cpu.getAllRegisters();

        for (String reg : allRegs) {
            s.m_regs.put(reg, "V" + reg);
        }

        /* Doesn't need to initialize memory state */
        return s;
    }

    /* override me if needs */
    public void setRegValue(String register, String value) {
        m_regs.put(register, value);
    }

    /* override me if needs */
    public String getRegValue(String register) {
        return m_regs.get(register);
    }

    /* override me if needs */
    public void setMemValue(String address, String value) {
        m_mems.put(address, value);
    }

    /* override me if needs */
    public String getMemValue(String address) {
        return touchMemAddr(address);
    }

    /**
     * Make the memory address as never untouched
     * 
     * @param address
     * @return
     */
    public String touchMemAddr(String address) {
        String value = m_mems.get(address);
        if (value == null) {
            String symbol;

            if (address.indexOf(' ') != -1) {
                symbol = String.format("V(%s)", address.replaceAll("\\s+", ""));
            } else {
                symbol = "V" + address;
            }

            m_mems.put(address, symbol);
            return symbol;
        } else {
            return value;
        }
    }

    /**
     * Make the memory address as never untouched
     * 
     * @param address
     * @return
     */
    public void untouchMemAddr(String address) {
        m_mems.remove(address);
    }

    /**
     * Fork a Machine state to caller
     *
     * @param state
     * @param reuse
     */
    public MachineState forkState() {
        MachineState s = new MachineState();
        s.m_regs = _deepCopy(m_regs);
        s.m_mems = _deepCopy(m_mems);

        return s;
    }

    /**
     * Make a deep copy of a Map, for internal use only
     *
     * @param proto
     * @return
     */
    private Map<String, String> _deepCopy(Map<String, String> proto) {
        Map<String, String> to = new HashMap<>();

        for (Map.Entry<String, String> ent : proto.entrySet()) {
            String k = new String(ent.getKey());
            String v = new String(ent.getValue());
            to.put(k, v);
        }
        return to;
    }

    public String toString() {
        return String.format("%s %s", m_regs.toString(), m_mems.toString());
    }
}

/*----------------------------copy from SMARTable.java-------------------------------------------------------------------*/
/**
 * SMARTable, wrap a VSA table for each code-line. Can be used as Map
 */
class SMARTable {
    private static final String VINF = "VINF";
    private static int WIDENVS_THRESHOLD = 4; // tigger widening
    private SymbolicCalculator m_calc;

    public Map<Long, Map<String, Set<String>>> m_tbl;

    public SMARTable() {
        m_calc = SymbolicCalculator.getCalculator();
        m_tbl = new HashMap<>();
    }

    public int size() {
        return m_tbl.size();
    }

    public void clear() {
        m_tbl.clear();
    }

    /**
     * Put new mapVS into table. The same line of code may access other memory
     * 
     * @param key
     * @param value
     */
    public void putDeep(Long key, Map<String, Set<String>> value) {
        /* The same line of code may access other memory */
        Map<String, Set<String>> mapVS = m_tbl.get(key);

        if (mapVS == null) {
            m_tbl.put(key, value);
        } else {
            mapVS.putAll(value);
        }
    }

    /* Interface for compatible with Map */
    public void put(Long key, Map<String, Set<String>> value) {
        putDeep(key, value);
    }

    /* Interface for compatible with Map */
    public Map<String, Set<String>> get(Long key) {
        return m_tbl.get(key);
    }

    /**
     * Use symbolic value VINF to widen value-set We do widening just for Equal
     * difference series
     * 
     * @param final_set
     * @param new_set
     * @return
     */
    private boolean widenVS(Set<String> final_set, Set<String> new_set) {
        /* Already widened to VINF */
        if (final_set.contains("VINF"))
            return false;

        /* Union new_set before widening */
        final_set.addAll(new_set);

        /* do widening if it has more than WIDENVS_THRESHOLD values */
        if (final_set.size() < WIDENVS_THRESHOLD)
            return false;

        /* do windenging for Equal difference series */
        int nLen = final_set.size();
        String vs[] = final_set.toArray(new String[nLen]);
        long pt[] = new long[nLen - 1];
        boolean bWidening = true;

        for (int i = 0; i < nLen - 1; i++) {
            String s = m_calc.symbolicSub(vs[i + 1], vs[i]);
            if (m_calc.isPureDigital(s)) {
                pt[i] = Long.decode(s);
            } else {
                bWidening = false;
                break;
            }
        }

        if (!bWidening)
            return false;

        /* Equal difference series ? */
        boolean bSeries = true;
        Arrays.sort(pt);
        for (int i = 1; bSeries && (i < pt.length); i++) {
            bSeries = (pt[i] == pt[i - 1]);
        }

        /* Do widening */
        if (bSeries)
            final_set.add(new String(VINF));

        return true;
    }

    /**
     * Test if final_set contains all elements from new_set, considering windening
     *
     * @param final_set
     * @param new_set
     * @return
     */
    private boolean containVS(Set<String> final_set, Set<String> new_set) {
        if (final_set.containsAll(new_set)) {
            return true;
        } else if (final_set.contains("VINF")) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Test if containing-relationship between two SMAR-Tables
     * 
     * @param new_smar_table
     * @return
     */
    public boolean containsAll(Map<Long, Map<String, Set<String>>> new_smar_table) {
        if (m_tbl.entrySet().containsAll(new_smar_table.entrySet())) {
            return true;
        }

        /* test if is widened? */
        boolean bContain;

        for (Map.Entry<Long, Map<String, Set<String>>> entNewSMARTbl : new_smar_table.entrySet()) {
            Long nNewLineno = entNewSMARTbl.getKey();
            Map<String, Set<String>> mapOldVSTble = m_tbl.get(nNewLineno);

            /* A new line of code is executed */
            if (mapOldVSTble == null)
                return false;

            /* Test if all values exist */
            Map<String, Set<String>> mapNewVSTble = entNewSMARTbl.getValue();
            for (Map.Entry<String, Set<String>> entNewVSTble : mapNewVSTble.entrySet()) {
                String strNewAddr = entNewVSTble.getKey();
                Set<String> setOldVS = mapOldVSTble.get(strNewAddr);

                /**
                 * The same line of code may may access another memory addrss, looping to access
                 * an array e.g. loop mov [rbp + rax], 0x10
                 */
                if (setOldVS == null)
                    continue;

                bContain = containVS(setOldVS, entNewVSTble.getValue());

                if (!bContain)
                    return false;
            }
        }
        return true;
    }

    /**
     * Test if containing-relationship between two SMAR-Tables
     * 
     * @param new_smar_table
     * @return
     */
    public boolean containsAll(SMARTable new_smar_table) {
        return containsAll(new_smar_table.m_tbl);
    }

    /**
     * Put all values from new_smar_table into m_tbl
     * 
     * @param new_smar_table
     */
    public void putAll(Map<Long, Map<String, Set<String>>> new_smar_table) {

        for (Map.Entry<Long, Map<String, Set<String>>> entNewSMARTbl : new_smar_table.entrySet()) {
            Long nNewLineno = entNewSMARTbl.getKey();
            Map<String, Set<String>> mapOldVSTble = m_tbl.get(nNewLineno);

            /* add all records from executing a new line of code */
            if (mapOldVSTble == null) {
                m_tbl.put(nNewLineno, entNewSMARTbl.getValue());
                continue;
            }

            /* Test if all values exist */
            Map<String, Set<String>> mapNewVSTble = entNewSMARTbl.getValue();
            for (Map.Entry<String, Set<String>> entNewVSTble : mapNewVSTble.entrySet()) {
                String strNewAddr = entNewVSTble.getKey();
                Set<String> setOldVS = mapOldVSTble.get(strNewAddr);

                if (setOldVS == null) {
                    mapOldVSTble.put(strNewAddr, entNewVSTble.getValue());
                } else {
                    widenVS(setOldVS, entNewVSTble.getValue());
                }
            }
        }
    }

    /**
     * Put all values from new_smar_table into m_tbl
     * 
     * @param new_smar_table
     */
    public void putAll(SMARTable new_smar_table) {
        Map<Long, Map<String, Set<String>>> mapNewSMARTbl = new_smar_table.m_tbl;
        putAll(mapNewSMARTbl);
    }
}

/*----------------------------copy from VSAException.java-------------------------------------------------------------------*/
class VSAException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public String toString() {
        return "VSAException is triggered";
    }
}

/*----------------------------copy from X86Processor.java-------------------------------------------------------------------*/
class X86Processor {

    private static final String[] m_Regs64 = { "RAX", "RBX", "RCX", "RDX", "RDI", "RSI", "RBP", "RSP", "R8", "R9",
            "R10", "R11", "R12", "R13", "R14", "R15" };
    private static final String[] m_Regs32 = { "EAX", "EBX", "ECX", "EDX", "EDI", "ESI", "EBP", "ESP", "R8D", "R9D",
            "R10D", "R11D", "R12D", "R13D", "R14D", "R15D" };
    private static final String[] m_Regs16 = { "AX", "BX", "CX", "DX", "DI", "SI", "BP", "SP" };
    private static final String[] m_Regs8h = { "AH", "BH", "CH", "DH" };
    private static final String[] m_Regs8l = { "AL", "BL", "CL", "DL" };
    private static final String[] m_RegSeg = { "FS", "GS" };
    private static final String[] m_RegXmm = { "XMM0", "XMM1", "XMM2", "XMM3", "XMM4", "XMM5", "XMM6", "XMM7" };

    private static Map<String, String> m_RegMap;
    private static String[] m_AllRegs;

    private static X86Processor m_singleton = null;

    private X86Processor() {
        createRegNameMapping();
        collectAllRegisters();
    }

    public static X86Processor getProcessor() {
        if (m_singleton == null) {
            m_singleton = new X86Processor();
        }
        return m_singleton;
    }

    /**
     * Create name mapping for register names
     */
    private void createRegNameMapping() {
        if (m_RegMap == null) {
            m_RegMap = new HashMap<>();
        }

        int idx = 0;

        for (idx = 0; idx < m_RegSeg.length; idx++) {
            m_RegMap.put(m_RegSeg[idx], m_RegSeg[idx]);
        }
        for (idx = 0; idx < m_RegXmm.length; idx++) {
            m_RegMap.put(m_RegXmm[idx], m_RegXmm[idx]);
        }
        for (idx = 0; idx < m_Regs64.length; idx++) {
            m_RegMap.put(m_Regs64[idx], m_Regs64[idx]);
        }
        for (idx = 0; idx < m_Regs32.length; idx++) {
            m_RegMap.put(m_Regs32[idx], m_Regs64[idx]);
        }
        for (idx = 0; idx < m_Regs16.length; idx++) {
            m_RegMap.put(m_Regs16[idx], m_Regs64[idx]);
        }
        for (idx = 0; idx < m_Regs8h.length; idx++) {
            m_RegMap.put(m_Regs8h[idx], m_Regs64[idx]);
        }
        for (idx = 0; idx < m_Regs8l.length; idx++) {
            m_RegMap.put(m_Regs8l[idx], m_Regs64[idx]);
        }
    }

    /**
     * Collect all available registers
     */
    private void collectAllRegisters() {
        if (m_AllRegs == null) {
            m_AllRegs = new String[m_RegSeg.length + m_RegXmm.length + m_Regs64.length];
        }

        String[] allRegs = m_AllRegs;
        System.arraycopy(m_RegSeg, 0, allRegs, 0, m_RegSeg.length);
        System.arraycopy(m_RegXmm, 0, allRegs, m_RegSeg.length, m_RegXmm.length);
        System.arraycopy(m_Regs64, 0, allRegs, m_RegSeg.length + m_RegXmm.length, m_Regs64.length);
        m_AllRegs = allRegs;
    }

    /* get the name of whole width register */
    public String getRegisterFullName(String register) {
        return m_RegMap.get(register);
    }

    /* Get all available registers on this architecture */
    public String[] getAllRegisters() {
        return m_AllRegs;
    }
}

/*----------------------------copy from X86Interpreter.java-------------------------------------------------------------------*/
class UnspportInstruction extends VSAException {
    private String m_lineno;
    private Instruction m_inst;

    UnspportInstruction(String lineno, Instruction instr) {
        m_lineno = lineno;
        m_inst = instr;
    }

    public String toString() {
        String msg = String.format("%s: unsupported instruction -> %s", m_lineno, m_inst.toString());
        return msg;
    }
}

class InvalidOperand extends VSAException {
    private String m_lineno;
    private Instruction m_inst;
    private Object[] m_objs;

    InvalidOperand(String lineno, Instruction instr, int operand_index) {
        m_lineno = lineno;
        m_inst = instr;
        m_objs = instr.getOpObjects(operand_index);
    }

    InvalidOperand(String lineno, Object[] objs_of_MemOperand) {
        m_lineno = lineno;
        m_inst = null;
        m_objs = objs_of_MemOperand;
    }

    public String toString() {
        /* print some details */
        String[] msg = new String[m_objs.length + 1];

        for (int i = 0; i < m_objs.length; i++) {
            Object o = m_objs[i];

            if (o instanceof String)
                msg[i] = new String((String) o);
            else if (o instanceof Character)
                msg[i] = new String(Character.toString((Character) o));
            else
                msg[i] = new String(o.getClass().getName());
        }
        if (m_inst == null)
            msg[m_objs.length] = "";
        else
            msg[m_objs.length] = " @ " + m_inst.toString();

        return String.join(";", msg);
    }
}

class Interpreter {
    public boolean doRecording(Instruction inst) {
        System.out.println("91:" + inst.toString());
        return true;
    }
}

class X86Interpreter extends Interpreter {

    private static X86Processor m_CPU; // x86-64 CPU
    private static OperandType m_OPRDTYPE; // Use for testing opranad types
    private static SymbolicCalculator m_SymCalc; // Used for do symbolic calculation

    private Map<Long, Map<String, Set<String>>> m_SMART; // Memory access recording
    private MachineState m_MachState; // Machine state

    private static X86Interpreter m_singleton = null;

    private X86Interpreter() {
        m_CPU = X86Processor.getProcessor();
        m_SymCalc = SymbolicCalculator.getCalculator();
        m_OPRDTYPE = new OperandType();
    }

    public static X86Interpreter getInterpreter() {
        if (m_singleton == null) {
            m_singleton = new X86Interpreter();
        }
        return m_singleton;
    }

    public X86Processor getCPU() {
        return m_CPU;
    }

    public boolean doRecording(MachineState state, Map<Long, Map<String, Set<String>>> table, Instruction inst) {
        m_MachState = state;
        m_SMART = table;

        int nOprand = inst.getNumOperands();

        try {
            if (nOprand == 0) {
                _doRecording0(inst);
            } else if (nOprand == 1) {
                _doRecording1(inst);
            } else if (nOprand == 2) {
                _doRecording2(inst);
            } else if (nOprand == 3) {
                _doRecording3(inst);
            } else if (nOprand == 4) {
                throw new UnspportInstruction("171", inst);
            } else {
                /* Throw exception */
                throw new UnspportInstruction("177", inst);
            }
            return true;

        } catch (Exception e) {
            String fname = e.getStackTrace()[0].getFileName();
            int line = e.getStackTrace()[0].getLineNumber();

            System.err.println(String.format("%s:%d: %s", fname, line, e.toString()));
            return false;
        }
    }

    public boolean doRecording(MachineState state, SMARTable table, Instruction inst) {
        return doRecording(state, table.m_tbl, inst);
    }

    private void _doRecording0(Instruction inst) {
        System.out.println("331: " + inst.toString());
        String op = inst.getMnemonicString();

        if (op.equalsIgnoreCase("nop")) {
            return;
        }

        else if (op.equalsIgnoreCase("cbw") || op.equalsIgnoreCase("cwde") || op.equalsIgnoreCase("cdqe")) {
            /* CBW/CWDE/CDQE: AX ← sign-extend of AL. */
            return;
        }

        else if (op.equalsIgnoreCase("ret")) {
            _record0ret(inst);
        }

        else if (op.equalsIgnoreCase("leave")) {
            _record0leave(inst);
        }

        else {
            throw new UnspportInstruction("333: 0 oprands", inst);
        }
    }

    private void _record0ret(Instruction inst) {
        /* pop rip */
        String strValue;
        /* Update RSP register status */
        strValue = getRegisterValue("RSP");
        strValue = m_SymCalc.symbolicAdd(strValue, 8);
        updateRegisterWriteAccess(inst.getAddress(), "RSP", strValue);
    }

    private void _record0leave(Instruction inst) {
        /* mov rsp, rbp; pop rbp */
        String strValSP, strValBP;
        String strValue;

        /* mov rsp, rbp */
        strValBP = getRegisterValue("RBP");
        updateRegisterWriteAccess(inst.getAddress(), "RSP", strValBP);

        /* pop rbp */
        strValSP = getRegisterValue("RSP");
        strValue = getMemoryValue(strValSP);
        updateRegisterWriteAccess(inst.getAddress(), "RBP", strValue);

        /* Clean memory status */
        strValSP = getRegisterValue("RSP");
        m_MachState.untouchMemAddr(strValSP);

        /* Update register RSP */
        strValSP = getRegisterValue("RSP");
        strValue = m_SymCalc.symbolicAdd(strValSP, 8);
        updateRegisterWriteAccess(inst.getAddress(), "RSP", strValue);
    }

    private void _doRecording1(Instruction inst) {
        System.out.println("340: " + inst.toString());

        String strAddr = null;
        String strValue = null;
        Set<String> tmpSet = null;

        String op = inst.getMnemonicString();

        if (op.equalsIgnoreCase("push")) {
            _record1push(inst);
        }

        else if (op.equalsIgnoreCase("pop")) {
            _record1pop(inst);
        }

        else if (op.equalsIgnoreCase("div")) {
            _record1div(inst);
        }

        else if (op.equalsIgnoreCase("nop")) {
            /* NOP [RAX + RAX*0x1] */
            return;
        }

        else if (op.equalsIgnoreCase("call")) {
            /* call xxx */
            System.out.println("400: fix-me, call xxx");
        } else if (op.charAt(0) == 'j' || op.charAt(0) == 'J') {
            /* jump xxx & jcc xx */
            System.out.println("405: fix-me, jxx");
        } else if (op.equalsIgnoreCase("ret")) {
            /* retn 0x8 */
            _record1retn(inst);
        }

        else {
            throw new UnspportInstruction("582: 1 oprands", inst);
        }
    }

    private void _record1push(Instruction inst) {
        String strAddr = null;
        String strValue = null;
        Set<String> tmpSet = null;

        /* push reg; push 0x1234; */
        String oprd = inst.getDefaultOperandRepresentation(0);
        int oprdty = inst.getOperandType(0);

        /* Get oprand value & upadte MAR-table */
        if (m_OPRDTYPE.isRegister(oprdty)) { // register
            strValue = getRegisterValue(oprd);
        } else if (m_OPRDTYPE.isScalar(oprdty)) { // Constant value
            strValue = oprd;
        } else { // must be address: two memory oprand does't supported by x86 and ARM
            System.out.println("326: throw exception, Wrong operand");
        }

        /* Update MAR-table & register status */
        strAddr = getRegisterValue("RSP");
        strAddr = m_SymCalc.symbolicSub(strAddr, 8);
        updateRegisterWriteAccess(inst.getAddress(), "RSP", strAddr);

        /* Update MAR-table & memory status */
        strAddr = getRegisterValue("RSP");
        updateMemoryWriteAccess(inst.getAddress(), strAddr, strValue);
    }

    private void _record1pop(Instruction inst) {
        String strAddr = null;
        String strValue = null;
        Set<String> tmpSet = null;

        /* pop reg */
        String oprd = inst.getDefaultOperandRepresentation(0);
        int oprdty = inst.getOperandType(0);

        /*
         * operand must be a reigster. Other type of memory access does't supported by
         * x86 and ARM
         */
        assert (m_OPRDTYPE.isRegister(oprdty));

        // strAddr = getRegisterValue("RSP");
        // updateMemoryReadAccess(inst.getAddress(), strAddr);

        /* Get value from stack && update rigister status */
        strValue = getRegisterValue("RSP");
        strValue = getMemoryValue(strValue);
        updateRegisterWriteAccess(inst.getAddress(), oprd, strValue);

        /* Clean memory status */
        strValue = getRegisterValue("RSP");
        m_MachState.untouchMemAddr(strValue);

        /* Update RSP register status */
        strValue = getRegisterValue("RSP");
        strValue = m_SymCalc.symbolicAdd(strValue, 8);
        updateRegisterWriteAccess(inst.getAddress(), "RSP", strValue);
    }

    private void _record1div(Instruction inst) {
        /* DIV r/m8 */
        String oprd = inst.getDefaultOperandRepresentation(0);
        int oprdty = inst.getOperandType(0);

        String strAddr, strValue;
        long iVal;

        Object[] objs;

        if (m_OPRDTYPE.isRegister(oprdty)) {
            /* sub reg, reg */
            oprd = inst.getDefaultOperandRepresentation(0);
            strValue = getRegisterValue(oprd);
        } else if (m_OPRDTYPE.isScalar(oprdty)) {
            /* sub rsp, 8; */
            oprd = inst.getDefaultOperandRepresentation(0);
            strValue = oprd;
        } else {
            /* others */
            objs = inst.getOpObjects(0);

            strAddr = _calcMemAddress(objs);

            /* update memory read access */
            updateMemoryReadAccess(inst.getAddress(), strAddr);

            /* fetch the value from the memory elememt */
            strValue = getMemoryValue(strAddr);
        }

        String strDx, strAx, strQue, strRem;
        long iDx, iAx, iQue, iRem;

        strDx = getRegisterValue("RDX");
        strAx = getRegisterValue("RAX");

        if (m_SymCalc.isPureSymbolic(strDx) || m_SymCalc.isPureSymbolic(strAx)) {
            strDx = strDx.replaceAll("\\s+", "");
            strAx = strAx.replaceAll("\\s+", "");

            strQue = String.format("D(%s:%s/%s)", strDx, strAx, strValue);
            strRem = String.format("D(%s:%s%%%s)", strDx, strAx, strValue);
        } else {
            iDx = Long.decode(strDx);
            iAx = Long.decode(strAx);
            if (m_SymCalc.isPureSymbolic(strValue)) {
                strDx = strDx.replaceAll("\\s+", "");
                strAx = strAx.replaceAll("\\s+", "");

                strQue = String.format("D(%s:%s/%s)", strDx, strAx, strValue);
                strRem = String.format("D(%s:%s%%%s)", strDx, strAx, strValue);
            } else {
                iQue = (iDx * iAx) / Long.decode(strValue);
                iRem = (iDx * iAx) % Long.decode(strValue);
                strQue = String.valueOf(iQue);
                strRem = String.valueOf(iRem);
            }
        }

        /* upate register status */
        updateRegisterWriteAccess(inst.getAddress(), "RAX", strQue);
        updateRegisterWriteAccess(inst.getAddress(), "RDX", strRem);
    }

    private void _record1retn(Instruction inst) {
        String strValue, strValSP, oprd;

        oprd = inst.getDefaultOperandRepresentation(0);

        /* Update RSP register status */
        strValSP = getRegisterValue("RSP");
        strValue = m_SymCalc.symbolicAdd(strValSP, Integer.decode(oprd) + 8);
        updateRegisterWriteAccess(inst.getAddress(), "RSP", strValue);
    }

    private void _doRecording2(Instruction inst) {
        System.out.println("414: " + inst.toString());

        String op = inst.getMnemonicString();

        if (op.equalsIgnoreCase("add")) {
            /* sub reg, reg; sub reg, 0x1234; sub reg, mem; sub mem, reg; sub mem, 0x1234 */
            _record2addsub(inst, '+');
        }

        else if (op.equalsIgnoreCase("sub")) {
            _record2addsub(inst, '-');
        }

        else if (op.equalsIgnoreCase("mov")) {
            _record2mov(inst);
        }

        else if (op.equalsIgnoreCase("movss")) {
            _record2mov(inst);
        }

        else if (op.equalsIgnoreCase("movaps")) {
            _record2mov(inst);
        }

        else if (op.equalsIgnoreCase("movsx")) {
            /* MOVSX r, r/m */
            _record2mov(inst);
        }

        else if (op.equalsIgnoreCase("movsxd")) {
            /* movsxd r, r/m */
            _record2mov(inst);
        }

        else if (op.equalsIgnoreCase("lea")) {
            _record2lea(inst);
        }

        else if (op.equalsIgnoreCase("xor")) {
            _record2xor(inst);
        }

        else if (op.equalsIgnoreCase("test")) {
            _record2test(inst);
        }

        else if (op.equalsIgnoreCase("cmp")) {
            _record2test(inst);
        }

        else if (op.equalsIgnoreCase("shl")) {
            _record2shl(inst);
        }

        else if (op.equalsIgnoreCase("shr")) {
            _record2shr(inst);
        }

        else {
            throw new UnspportInstruction("689: 2 oprands", inst);
        }
    }

    private void _record2addsub(Instruction inst, char op) {
        int oprd0ty = inst.getOperandType(0);
        int oprd1ty = inst.getOperandType(1);

        String strVal0, strVal1, strAddr0, strAddr1;
        String strValue, strAddress;
        String oprd0, oprd1;
        long iVal0, iVal1;

        Object[] objs;

        if (m_OPRDTYPE.isRegister(oprd0ty)) {
            oprd0 = inst.getDefaultOperandRepresentation(0);
            strVal0 = getRegisterValue(oprd0);

            if (m_OPRDTYPE.isRegister(oprd1ty)) {
                /* sub reg, reg */
                oprd1 = inst.getDefaultOperandRepresentation(1);
                strVal1 = getRegisterValue(oprd1);

                if (op == '+')
                    strValue = m_SymCalc.symbolicAdd(strVal0, strVal1);
                else if (op == '-')
                    strValue = m_SymCalc.symbolicSub(strVal0, strVal1);
                else
                    strValue = strVal0; // fix-me

                updateRegisterWriteAccess(inst.getAddress(), oprd0, strValue);
            } else if (m_OPRDTYPE.isScalar(oprd1ty)) {
                /* sub rsp, 8; */
                oprd1 = inst.getDefaultOperandRepresentation(1);

                if (op == '+')
                    strValue = m_SymCalc.symbolicAdd(strVal0, Long.decode(oprd1));
                else if (op == '-')
                    strValue = m_SymCalc.symbolicSub(strVal0, Long.decode(oprd1));
                else
                    strValue = strVal0;

                /* upate register status */
                updateRegisterWriteAccess(inst.getAddress(), oprd0, strValue);
            } else {
                /* others */
                objs = inst.getOpObjects(1);

                strAddr1 = _calcMemAddress(objs);

                /* update memory read access */
                updateMemoryReadAccess(inst.getAddress(), strAddr1);

                /* fetch the value from the memory elememt */
                strVal1 = getMemoryValue(strAddr1);

                if (op == '+')
                    strValue = m_SymCalc.symbolicAdd(strVal0, strVal1);
                else if (op == '-')
                    strValue = m_SymCalc.symbolicSub(strVal0, strVal1);
                else
                    strValue = strVal0;

                /* upate register status */
                updateRegisterWriteAccess(inst.getAddress(), oprd0, strValue);
            }
        } else {
            /* The first operand is in memory */
            /* Ghidra bug: sub [RAX],RDX -> _, ADDR|REG */
            if (m_OPRDTYPE.isRegister(oprd1ty)) {
                oprd1 = inst.getDefaultOperandRepresentation(1);
                strVal1 = getRegisterValue(oprd1);
            } else if (m_OPRDTYPE.isScalar(oprd1ty)) {
                oprd1 = inst.getDefaultOperandRepresentation(1);
                strVal1 = oprd1;
            } else {
                /* Operand 1 is invalid, throw exeception */
                throw new InvalidOperand("773", inst, 1);
            }

            objs = inst.getOpObjects(0);
            strAddr0 = _calcMemAddress(objs);

            /* fetch the value from the memory elememt */
            strVal0 = getMemoryValue(strAddr0);

            if (op == '+')
                strValue = m_SymCalc.symbolicAdd(strVal0, strVal1);
            else if (op == '-')
                strValue = m_SymCalc.symbolicSub(strVal0, strVal1);
            else
                strValue = strVal0;

            /* update memory write access */
            updateMemoryWriteAccess(inst.getAddress(), strAddr0, strValue);
        }
    }

    private void _record2mov(Instruction inst) {
        int oprd0ty = inst.getOperandType(0);
        int oprd1ty = inst.getOperandType(1);

        String strVal0, strVal1, strAddr0, strAddr1;
        String strValue, strAddress;
        String oprd0, oprd1;
        long iVal0, iVal1;

        Object[] objs;

        /* mov reg, reg; mov reg, mem; mov reg, 0x1234; mov mem, reg; mov mem, 0x1234 */
        if (m_OPRDTYPE.isRegister(oprd0ty)) {
            oprd0 = inst.getDefaultOperandRepresentation(0);
            if (m_OPRDTYPE.isRegister(oprd1ty)) {
                /* mov reg, reg */
                oprd1 = inst.getDefaultOperandRepresentation(1);

                strVal1 = getRegisterValue(oprd1);
                updateRegisterWriteAccess(inst.getAddress(), oprd0, strVal1);
            } else if (m_OPRDTYPE.isScalar(oprd1ty)) {
                /* mov rax, 8; */
                oprd1 = inst.getDefaultOperandRepresentation(1);
                strVal1 = oprd1;

                /* upate register status */
                updateRegisterWriteAccess(inst.getAddress(), oprd0, strVal1);
            } else { /* memory oprand */
                objs = inst.getOpObjects(1);
                strAddr1 = _calcMemAddress(objs);

                /* update memory read access */
                updateMemoryReadAccess(inst.getAddress(), strAddr1);

                /* fetch the value from the memory elememt */
                strVal1 = getMemoryValue(strAddr1);

                /* upate register status */
                updateRegisterWriteAccess(inst.getAddress(), oprd0, strVal1);
            }
        } else {
            /* Ghidra bug: MOV [RAX],RDX -> _, ADDR|REG */
            if (m_OPRDTYPE.isRegister(oprd1ty)) {
                oprd1 = inst.getDefaultOperandRepresentation(1);
                strVal1 = getRegisterValue(oprd1);
            } else if (m_OPRDTYPE.isScalar(oprd1ty)) {
                oprd1 = inst.getDefaultOperandRepresentation(1);
                strVal1 = m_SymCalc.symbolicAdd("0", oprd1);
            } else {
                /* Operand 1 is invalid, throw exeception */
                throw new InvalidOperand("858", inst, 1);
            }

            objs = inst.getOpObjects(0);

            strAddr0 = _calcMemAddress(objs);

            /* update memory write access */
            updateMemoryWriteAccess(inst.getAddress(), strAddr0, strVal1);
        }
    }

    private void _record2lea(Instruction inst) {
        int oprd0ty = inst.getOperandType(0);
        int oprd1ty = inst.getOperandType(1);

        String strVal0, strVal1, strAddr0, strAddr1;
        String strValue, strAddress;
        String oprd0, oprd1;
        long iVal0, iVal1;

        Object[] objs;

        /* get the name of register */
        assert (m_OPRDTYPE.isRegister(oprd0ty));
        oprd0 = inst.getDefaultOperandRepresentation(0);

        /* get the value of second operand */
        objs = inst.getOpObjects(1);
        strAddr1 = _calcMemAddress(objs);
        strValue = strAddr1;

        /* upate register status */
        updateRegisterWriteAccess(inst.getAddress(), oprd0, strValue);
    }

    private void _record2xor(Instruction inst) {
        int oprd0ty = inst.getOperandType(0);
        int oprd1ty = inst.getOperandType(1);

        String strVal0, strVal1, strAddr0, strAddr1;
        String strValue, strAddress;
        String oprd0, oprd1;
        long iVal0, iVal1;

        Object[] objs;

        /* mov reg, reg; mov reg, mem; mov reg, 0x1234; mov mem, reg; mov mem, 0x1234 */
        if (m_OPRDTYPE.isRegister(oprd0ty)) {
            oprd0 = inst.getDefaultOperandRepresentation(0);
            strVal0 = getRegisterValue(oprd0);
            if (m_OPRDTYPE.isRegister(oprd1ty)) {
                /* xor reg, reg */
                oprd1 = inst.getDefaultOperandRepresentation(1);

                strVal1 = getRegisterValue(oprd1);
            } else if (m_OPRDTYPE.isScalar(oprd1ty)) {
                /* mov rax, 8; */
                oprd1 = inst.getDefaultOperandRepresentation(1);
                strVal1 = oprd1;
            } else { /* memory oprand */
                objs = inst.getOpObjects(1);
                strAddr1 = _calcMemAddress(objs);

                /* update memory read access */
                updateMemoryReadAccess(inst.getAddress(), strAddr1);

                /* fetch the value from the memory elememt */
                strVal1 = getMemoryValue(strAddr1);
            }

            /* upate register status */
            strValue = m_SymCalc.symbolicXor(strVal0, strVal1);
            updateRegisterWriteAccess(inst.getAddress(), oprd0, strValue);
        } else {
            /* Ghidra bug: MOV [RAX],RDX -> _, ADDR|REG */
            if (m_OPRDTYPE.isRegister(oprd1ty)) {
                oprd1 = inst.getDefaultOperandRepresentation(1);
                strVal1 = getRegisterValue(oprd1);
            } else if (m_OPRDTYPE.isScalar(oprd1ty)) {
                oprd1 = inst.getDefaultOperandRepresentation(1);
                strVal1 = oprd1;
            } else {
                /* Operand 1 is invalid, throw exeception */
                throw new InvalidOperand("949", inst, 1);
            }

            objs = inst.getOpObjects(0);

            strAddr0 = _calcMemAddress(objs);

            /* update memory read access */
            updateMemoryReadAccess(inst.getAddress(), strAddr0);

            /* fetch the value from the memory elememt */
            strVal0 = getMemoryValue(strAddr0);
            /* update memory write access */
            strValue = m_SymCalc.symbolicXor(strVal0, strVal1);

            updateMemoryWriteAccess(inst.getAddress(), strAddr0, strValue);
        }
    }

    private void _record2test(Instruction inst) {
        /*
         * test reg, reg; test reg, mem; test reg, 0x1234; test mem, reg; test mem,
         * 0x1234
         */
        int oprd0ty = inst.getOperandType(0);
        int oprd1ty = inst.getOperandType(1);

        String strVal0, strVal1, strAddr0, strAddr1;
        String strValue, strAddress;
        String oprd0, oprd1;
        long iVal0, iVal1;

        Object[] objs;

        /* test oprand 0 */
        if (m_OPRDTYPE.isRegister(oprd0ty)) {
            /* do nothing */
        } else if (m_OPRDTYPE.isScalar(oprd0ty)) {
            throw new InvalidOperand("987", inst, 0);
        } else {
            /* memory oprand */
            objs = inst.getOpObjects(0);
            strAddr0 = _calcMemAddress(objs);

            /* update memory read access */
            updateMemoryReadAccess(inst.getAddress(), strAddr0);
        }

        /* test oprand 1 */
        if (m_OPRDTYPE.isRegister(oprd1ty)) {
            /* do nothing */
        } else if (m_OPRDTYPE.isScalar(oprd1ty)) {
            /* do nothing */
        } else {
            /* memory oprand */
            objs = inst.getOpObjects(1);
            strAddr1 = _calcMemAddress(objs);

            /* update memory read access */
            updateMemoryReadAccess(inst.getAddress(), strAddr1);
        }
    }

    private void _record2shl(Instruction inst) {
        /* shl rax, 0x4 */

        int oprd0ty = inst.getOperandType(0);
        int oprd1ty = inst.getOperandType(1);

        String strVal0, strVal1, strAddr0, strAddr1;
        String strValue, strAddress;
        String oprd0, oprd1;
        long iVal0, iVal1;

        Object[] objs;

        /* test oprand 0 */
        if (m_OPRDTYPE.isRegister(oprd0ty) && m_OPRDTYPE.isScalar(oprd1ty)) {
            oprd0 = inst.getDefaultOperandRepresentation(0);
            oprd1 = inst.getDefaultOperandRepresentation(1);

            strVal0 = getRegisterValue(oprd0);
            iVal1 = Long.decode(oprd1);
            iVal1 = (long) Math.pow(2, iVal1);

            strValue = m_SymCalc.symbolicMul(strVal0, iVal1);

            /* upate register status */
            updateRegisterWriteAccess(inst.getAddress(), oprd0, strValue);
        } else {
            throw new InvalidOperand("1061", inst, 0);
        }
    }

    private void _record2shr(Instruction inst) {
        /* shr rax, 0x4 */
        int oprd0ty = inst.getOperandType(0);
        int oprd1ty = inst.getOperandType(1);

        String strVal0, strVal1, strAddr0, strAddr1;
        String strValue, strAddress;
        String oprd0, oprd1;
        long iVal0, iVal1;

        Object[] objs;

        /* test oprand 0 */
        if (m_OPRDTYPE.isRegister(oprd0ty) && m_OPRDTYPE.isScalar(oprd1ty)) {
            oprd0 = inst.getDefaultOperandRepresentation(0);
            oprd1 = inst.getDefaultOperandRepresentation(1);

            strVal0 = getRegisterValue(oprd0);
            iVal1 = Long.decode(oprd1);
            iVal1 = (long) Math.pow(2, iVal1);

            strValue = m_SymCalc.symbolicDiv(strVal0, iVal1);

            /* upate register status */
            updateRegisterWriteAccess(inst.getAddress(), oprd0, strValue);
        } else {
            throw new InvalidOperand("1092", inst, 0);
        }
    }

    private void _doRecording3(Instruction inst) {
        System.out.println("1035: " + inst.toString());

        String op = inst.getMnemonicString();

        if (op.equalsIgnoreCase("imul")) {
            /* sub reg, reg; sub reg, 0x1234; sub reg, mem; sub mem, reg; sub mem, 0x1234 */
            _record3imul(inst);
        } else {
            throw new UnspportInstruction("1044: 3 oprands", inst);
        }
    }

    private void _record3imul(Instruction inst) {
        /* IMUL r16,r/m16,imm16 */
        int oprd0ty = inst.getOperandType(0);
        int oprd1ty = inst.getOperandType(1);
        int oprd2ty = inst.getOperandType(2);

        String strVal0, strVal1, strVal2, strAddr0, strAddr1, strAddr2;
        String strValue, strAddress;
        String oprd0, oprd1, oprd2;
        long iVal0, iVal1, iVal2;

        Object[] objs;

        /* test oprand 0 */
        assert (m_OPRDTYPE.isRegister(oprd0ty) && m_OPRDTYPE.isScalar(oprd2ty));

        if (m_OPRDTYPE.isRegister(oprd1ty)) {
            oprd1 = inst.getDefaultOperandRepresentation(1);

            strVal1 = getRegisterValue(oprd1);
        } else if (m_OPRDTYPE.isScalar(oprd1ty)) {
            throw new InvalidOperand("1069", inst, 1);
        } else {
            /* memory oprand */
            objs = inst.getOpObjects(1);
            strAddr1 = _calcMemAddress(objs);

            /* update memory read access */
            updateMemoryReadAccess(inst.getAddress(), strAddr1);

            /* fetch the value from the memory elememt */
            strVal1 = getMemoryValue(strAddr1);
        }

        oprd2 = inst.getDefaultOperandRepresentation(2);
        iVal2 = Long.decode(oprd2);
        strValue = m_SymCalc.symbolicMul(strVal1, iVal2);

        /* upate register status */
        oprd0 = inst.getDefaultOperandRepresentation(0);
        updateRegisterWriteAccess(inst.getAddress(), oprd0, strValue);
    }

    private String _calcMemAddress(Object[] objs_of_MemOperand) {
        /* A memory oprand from Ghidra, consits with an array of objects */
        Object[] objs = objs_of_MemOperand;
        String strValue, strAddress;

        if (objs.length == 1) {
            /* mov reg, [reg]; mov reg, [0x48000] */
            if (objs[0] instanceof Register) {
                Register r = (Register) objs[0];

                /* get regiser value */
                strValue = getRegisterValue(r.getName());
                return strValue;
            } else if (objs[0] instanceof Scalar) {
                Scalar s = (Scalar) objs[0];

                /* get memory address */
                strAddress = String.valueOf(s.getValue());
                return strAddress;

            } else if (objs[0] instanceof GenericAddress) {
                GenericAddress a = (GenericAddress) objs[0];

                strAddress = String.valueOf(a.getOffset());
                return strAddress;
            }

            else {
                /* This operand is invalid, throw exeception */
                throw new InvalidOperand("992", objs_of_MemOperand);
            }
        } else if (objs.length == 2) {
            /*
             * Registet + Scaler: i.e [RBP + -0x28] Registet + Scaler: [-0xf8 + RBP]
             */
            Register r;
            Scalar s;

            if ((objs[0] instanceof Register) && (objs[1] instanceof Scalar)) {
                r = (Register) objs[0];
                s = (Scalar) objs[1];
            } else if ((objs[0] instanceof Scalar) && (objs[1] instanceof Register)) {
                r = (Register) objs[1];
                s = (Scalar) objs[0];
            } else {
                throw new InvalidOperand("1019", objs_of_MemOperand);
            }

            strValue = getRegisterValue(r.getName());
            strAddress = m_SymCalc.symbolicAdd(strValue, s.getValue());

            return strAddress;
        } else if (objs.length == 3) {
            /* Registet + Register * Scaler: [RDX + RAX*0x1] */
            if ((objs[0] instanceof Register) && (objs[1] instanceof Register) && (objs[2] instanceof Scalar)) {
                Register rb, ri;
                Scalar s;
                String vb, vi;

                rb = (Register) objs[0];
                ri = (Register) objs[1];
                s = (Scalar) objs[2];

                System.out.println(String.format("%s + %s*%d?", rb.getName(), ri.getName(), s.getValue()));
                vb = getRegisterValue(rb.getName());
                vi = getRegisterValue(ri.getName());

                strValue = m_SymCalc.symbolicMul(vi, s.getValue());
                strAddress = m_SymCalc.symbolicAdd(vb, strValue);

                return strAddress;
            } else {
                throw new InvalidOperand("1319", objs_of_MemOperand);
            }
        } else if (objs.length == 4) {
            /* [RBP + RAX*0x4 + -0x60] */
            if ((objs[0] instanceof Register) && (objs[1] instanceof Register) && (objs[2] instanceof Scalar)
                    && (objs[3] instanceof Scalar)) {
                Register rb, ri;
                Scalar sc, so;
                String vb, vi;

                rb = (Register) objs[0];
                ri = (Register) objs[1];
                sc = (Scalar) objs[2];
                so = (Scalar) objs[3];

                System.out.println(String.format("%s + %s*0x%x + 0x%x?", rb.getName(), ri.getName(), sc.getValue(),
                        so.getValue()));
                vb = getRegisterValue(rb.getName());
                vi = getRegisterValue(ri.getName());

                strValue = m_SymCalc.symbolicMul(vi, sc.getValue());
                strAddress = m_SymCalc.symbolicAdd(vb, strValue);
                strAddress = m_SymCalc.symbolicAdd(strAddress, so.getValue());

                return strAddress;
            } else {
                throw new InvalidOperand("1574", objs_of_MemOperand);
            }
        } else {
            /* This operand is invalid, throw exeception */
            throw new InvalidOperand("1579", objs_of_MemOperand);
        }
    }

    /* override me if needs */
    private String getRegisterValue(String register) {
        String Reg = m_CPU.getRegisterFullName(register);
        return m_MachState.getRegValue(Reg);
    }

    /* override me if needs */
    private String getMemoryValue(String address) {
        return m_MachState.getMemValue(address);
    }

    private boolean updateRegisterWriteAccess(long inst_address, String reg, String value) {
        Map<String, Set<String>> tmpMap;
        Set<String> tmpSet;

        /* Update SMAR-table for Register reg */
        tmpMap = m_SMART.get(inst_address);
        if (tmpMap == null) {
            tmpMap = new HashMap<>();
            m_SMART.put(inst_address, tmpMap);
        }

        reg = m_CPU.getRegisterFullName(reg);
        tmpSet = tmpMap.get(reg);
        if (tmpSet == null) {
            tmpSet = new HashSet<>();
            tmpMap.put(reg, tmpSet);
        }

        assert (tmpSet != null);
        tmpSet.add(value);

        /* for debugging */
        System.out.println(String.format("674: @0x%x: %s = %s", inst_address, reg, value));

        /* Update register state */
        m_MachState.setRegValue(reg, value);

        return true;
    }

    private boolean updateRegisterWriteAccess(Address instruction_address, String reg, String value) {
        return updateRegisterWriteAccess(instruction_address.getOffset(), reg, value);
    }

    private boolean updateMemoryWriteAccess(long inst_address, String address, String value) {
        Map<String, Set<String>> tmpMap;
        Set<String> tmpSet;

        /* Update MAR-table for address */
        tmpMap = m_SMART.get(inst_address);
        if (tmpMap == null) {
            tmpMap = new HashMap<>();
            m_SMART.put(inst_address, tmpMap);
        }

        tmpSet = tmpMap.get(address);
        if (tmpSet == null) {
            tmpSet = new HashSet<>();
            tmpMap.put(address, tmpSet);
        }

        assert (tmpSet != null);
        tmpSet.add(value);

        /* for debuging */
        System.out.println(String.format("686: @0x%x: [%s] = %s", inst_address, address, value));

        /* Update memory status */
        m_MachState.setMemValue(address, value);

        return true;
    }

    private boolean updateMemoryWriteAccess(Address inst_address, String memory_address, String value) {
        return updateMemoryWriteAccess(inst_address.getOffset(), memory_address, value);
    }

    private boolean updateMemoryReadAccess(long inst_address, String address) {
        Map<String, Set<String>> tmpMap;
        Set<String> tmpSet;
        String value;

        value = m_MachState.getMemValue(address);

        /* Update MAR-table for memory read */
        tmpMap = m_SMART.get(inst_address);
        if (tmpMap == null) {
            tmpMap = new HashMap<>();
            m_SMART.put(inst_address, tmpMap);
        }

        tmpSet = tmpMap.get(address);
        if (tmpSet == null) {
            tmpSet = new HashSet<>();
            tmpMap.put(address, tmpSet);

            tmpSet.add(value); // Set a symbolic value
        }

        return true;
    }

    private boolean updateMemoryReadAccess(Address inst_address, String memory_address) {
        return updateMemoryReadAccess(inst_address.getOffset(), memory_address);
    }
}

/*----------------------------copy from SymbolicCalculator.java-------------------------------------------------------------------*/
class InvalidSymboicValue extends VSAException {
    private static final long serialVersionUID = 1L;
    private String m_lineno, m_symbol;

    public InvalidSymboicValue(String lineno, String symbol) {
        m_lineno = lineno;
        m_symbol = symbol;
    }

    public String toString() {
        return String.format("%s: InvalidSymboicValue -> %s", m_lineno, m_symbol);
    }
}

class InvalidSymboicOP extends VSAException {
    private static final long serialVersionUID = 1L;
    private String m_lineno, m_msg;

    public InvalidSymboicOP(String lineno, String message) {
        m_lineno = lineno;
        m_msg = message;
    }

    public String toString() {
        return String.format("%s: InvalidSymboicOP -> %s", m_lineno, m_msg);
    }
}

/**
 * Encapsulate calculatoin for symbolic values Singleton mode
 */
class SymbolicCalculator {

    private static SymbolicCalculator m_calc = null; // Singleton mode

    final DecimalFormat m_digitFmt; // Add a +/- sign before digit values

    private SymbolicCalculator() {
        m_digitFmt = new DecimalFormat("+#;-#");
    }

    public static SymbolicCalculator getCalculator() {
        if (m_calc == null) {
            m_calc = new SymbolicCalculator();
        }
        return m_calc;
    }

    public String symbolicAdd(String symbol0, String symbol1) {
        assert (isSymbolicValue(symbol0) && isSymbolicValue(symbol1));
        return symbolicBinaryOP(symbol0, '+', symbol1);
    }

    public String symbolicSub(String symbol0, String symbol1) {
        assert (isSymbolicValue(symbol0) && isSymbolicValue(symbol1));
        return symbolicBinaryOP(symbol0, '-', symbol1);
    }

    public String symbolicMul(String symbol0, String symbol1) {
        assert (isSymbolicValue(symbol0) && isSymbolicValue(symbol1));
        return symbolicBinaryOP(symbol0, '*', symbol1);
    }

    public String symbolicDiv(String symbol0, String symbol1) {
        assert (isSymbolicValue(symbol0) && isSymbolicValue(symbol1));
        return symbolicBinaryOP(symbol0, '/', symbol1);
    }

    public String symbolicXor(String symbol0, String symbol1) {
        assert (isSymbolicValue(symbol0) && isSymbolicValue(symbol1));
        return symbolicBinaryOP(symbol0, '^', symbol1);
    }

    /**
     * Binary operations for two symbolic values.
     *
     * @param symbol0
     * @param op
     * @param symbol1
     * @return
     */
    public String symbolicBinaryOP(String symbol0, char op, String symbol1) {
        String[] elems0 = symbol0.split("\\s", 0);
        String[] elems1 = symbol1.split("\\s", 0);

        /* parse the symbolic value symbol0 */
        String part0S; // Symbolic part in symbol0
        long part0V; // Value part in symbol0

        if (elems0.length == 1) {
            if (isPureDigital(elems0[0])) {
                part0S = "0";
                part0V = Long.decode(elems0[0]);
            } else if (isPureSymbolic(elems0[0])) {
                part0S = elems0[0];
                part0V = 0;
            } else {
                System.out.println(String.format("2083: (%s) (%s)", symbol0, symbol1));
                throw new InvalidSymboicValue("1833", symbol0);
            }
        } else if (elems0.length == 2) {
            part0S = elems0[0];
            part0V = Long.decode(elems0[1]);
        } else {
            /* We assume each value has at most two parts. */
            throw new InvalidSymboicValue("1841", symbol0);
        }

        /* parse the symbolic value symbol1 */
        String part1S; // Symbolic part in symbol0
        long part1V; // Value part in symbol0

        if (elems1.length == 1) {
            if (isPureDigital(elems1[0])) {
                part1S = "0";
                part1V = Long.decode(elems1[0]);
            } else if (isPureSymbolic(elems1[0])) {
                part1S = elems1[0];
                part1V = 0;
            } else {
                throw new InvalidSymboicValue("1859", symbol1);
            }
        } else if (elems1.length == 2) {
            part1S = elems1[0];
            part1V = Long.decode(elems1[1]);
        } else {
            /* We assume each value has at most two parts. */
            throw new InvalidSymboicValue("1867", symbol1);
        }

        /* calculate the result */
        String tmpS, newSymbol;
        long tmpV;

        if (op == '+' || op == '-') {
            tmpS = binaryOP(part0S, op, part1S);
            tmpV = binaryOP(part0V, op, part1V);
            newSymbol = binaryOP(tmpS, '+', tmpV);

        } else if (op == '*') {
            if (part0S.equals("0") || part1S.equals("0")) {
                if (part0S.equals("0")) {
                    tmpS = binaryOP(part1S, '*', part0V);
                } else {
                    tmpS = binaryOP(part0S, '*', part1V);
                }

                tmpV = binaryOP(part0V, '*', part1V);
                newSymbol = binaryOP(tmpS, '+', tmpV);

            } else {
                String tmpL, tmpR;

                tmpS = binaryOP(part0S, '*', part1S);
                tmpL = binaryOP(part0S, '*', part1V);
                tmpR = binaryOP(part1S, '*', part0V);
                tmpV = binaryOP(part0V, '*', part1V);

                newSymbol = binaryOP(tmpS, '+', tmpL);
                newSymbol = binaryOP(newSymbol, '+', tmpR);
                newSymbol = binaryOP(newSymbol, '+', tmpV);
            }

        } else if (op == '/') {
            if (symbol0.equals(symbol1)) {
                newSymbol = "1";

            } else if (part0S.equals("0") && part0V == 0) {
                newSymbol = "0";

            } else if (part0S.equals("0") && part1S.equals("0")) {
                tmpV = binaryOP(part0V, '/', part1V);
                newSymbol = binaryOP("0", '+', tmpV);

            } else if (!part0S.equals("0") && part1S.equals("0")) {
                /* (VRSP + 100)/10 or VRSP/10 */
                if (part0V == 0) {
                    newSymbol = String.format("D(%s/%d)", part0S, part1V);
                } else {
                    if (part0V % part1V == 0) {
                        newSymbol = String.format("D(%s/%d) %s", part0S, part1V, m_digitFmt.format(part0V / part1V));
                    } else {
                        newSymbol = String.format("D(%s%s/%d)", part0S, m_digitFmt.format(part0V), part1V);
                    }
                }
            } else if (part0S.equals("0") && !part1S.equals("0")) {
                if (part1V == 0) {
                    newSymbol = String.format("D(%d/%s)", part0V, part1S);
                } else {
                    newSymbol = String.format("D(%d/%s%s)", part0V, part1S, m_digitFmt.format(part1V));
                }

            } else {
                part0S = symbol0.replaceAll("\\s", "");
                part1S = symbol1.replaceAll("\\s", "");
                newSymbol = String.format("D(%s/%s)", part0S, part1S);
            }

        } else if (op == '^') {
            if (symbol0.equals(symbol1)) {
                newSymbol = "0";
            } else {
                part0S = symbol0.replaceAll("\\s", "");
                part1S = symbol1.replaceAll("\\s", "");
                newSymbol = String.format("D(%s^%s)", part0S, part1S);
            }
        } else {
            /* Thow exception */
            String msg = String.format("(%s) %s (%s)", symbol0, Character.toString(op), symbol1);
            throw new InvalidSymboicOP("2140", msg);
        }

        return newSymbol;
    }

    public String symbolicAdd(String symbol, long value) {
        assert (isSymbolicValue(symbol));
        return symbolicBinaryOP(symbol, '+', value);
    }

    public String symbolicSub(String symbol, long value) {
        assert (isSymbolicValue(symbol));
        return symbolicBinaryOP(symbol, '-', value);
    }

    public String symbolicMul(String symbol, long value) {
        assert (isSymbolicValue(symbol));
        return symbolicBinaryOP(symbol, '*', value);
    }

    public String symbolicDiv(String symbol, long value) {
        assert (isSymbolicValue(symbol));
        return symbolicBinaryOP(symbol, '/', value);
    }

    /**
     * Binary operation for a symbolic-value and an integer value
     *
     * @param symbol
     * @param op
     * @param value
     * @return A symbolic-value
     */
    public String symbolicBinaryOP(String symbol, char op, long value) {
        String[] elems = symbol.split("\\s", 0);

        /* parse the symbolic value */
        String partS; // symbolic part of symbol
        long partV; // Numeric part of symbol

        if (elems.length == 1) {
            if (isPureDigital(elems[0])) {
                partS = "";
                partV = Long.decode(elems[0]);
            } else if (isPureSymbolic(elems[0])) {
                partS = elems[0];
                partV = 0;
            } else {
                throw new InvalidSymboicValue("1933", symbol);
            }

        } else if (elems.length == 2) {
            partS = elems[0];
            partV = Long.decode(elems[1]);

        } else {
            /* We assume the symbolic value has at most two parts */
            String msg = String.format("%s has more than two parts", symbol);
            throw new InvalidSymboicOP("1970", msg);
        }

        String newSymbol;
        long newValue;

        if (partS.equals("")) {
            newValue = binaryOP(partV, op, value);
            newSymbol = binaryOP("0", '+', newValue);

        } else if (partV == 0) {
            newSymbol = binaryOP(partS, op, value);

        } else {
            if (op == '+' || op == '-') {
                newValue = binaryOP(partV, op, value);
                newSymbol = binaryOP(partS, '+', newValue);

            } else if (op == '*') {
                newValue = binaryOP(partV, op, value);
                newSymbol = binaryOP(partS, op, value);
                newSymbol = binaryOP(newSymbol, '+', newValue);

            } else if (op == '/') {
                if (partV % value == 0) {
                    newValue = binaryOP(partV, op, value);
                    newSymbol = binaryOP(partS, op, value);
                    newSymbol = binaryOP(newSymbol, '+', newValue);
                } else {
                    newSymbol = String.format("D(%s%s/%d)", partS, m_digitFmt.format(partV), value);
                }

            } else if (op == '^') {
                newSymbol = String.format("D(%s%s^%d)", partS, m_digitFmt.format(partV), value);

            } else {
                String msg = String.format("(%s) %s %d", symbol, Character.toString(op), value);
                throw new InvalidSymboicOP("2024", msg);
            }
        }

        return newSymbol;
    }

    /**
     * Binary operation for two pure-symbolic values
     *
     * @param pure_symbol0
     * @param op
     * @param pure_symbol1
     * @return
     */
    private String binaryOP(String pure_symbol0, char op, String pure_symbol1) {
        assert (isPureSymbolic(pure_symbol0));
        assert (isPureSymbolic(pure_symbol1));

        String newSymbol;
        long newValue;

        if (isZero(pure_symbol0))
            pure_symbol0 = "";
        if (isZero(pure_symbol1))
            pure_symbol1 = "";

        if (op == '+') {
            if (pure_symbol0.equals("") || pure_symbol1.equals("")) {
                newSymbol = pure_symbol0 + pure_symbol1;
                if (newSymbol.equals(""))
                    newSymbol = "0";

            } else if (pure_symbol0.equals("-" + pure_symbol1) || pure_symbol1.equals("-" + pure_symbol0)) {
                newSymbol = "0";
            } else {
                /* Cannot parse */
                newSymbol = String.format("D(%s+%s)", pure_symbol0, pure_symbol1);
            }

        } else if (op == '-') {
            if (pure_symbol0.equals(pure_symbol1)) {
                newSymbol = "0";
            } else if (pure_symbol0.equals("")) {
                newSymbol = String.format("-%s", pure_symbol1);
            } else if (pure_symbol1.equals("")) {
                newSymbol = pure_symbol0;
            } else {
                /* Cannot parse */
                newSymbol = String.format("D(%s-%s)", pure_symbol0, pure_symbol1);
            }

        } else if (op == '*') {
            if (pure_symbol0.equals("") || pure_symbol1.equals("")) {
                newSymbol = "0";
            } else {
                newSymbol = String.format("D(%s*%s)", pure_symbol0, pure_symbol1);
            }

        } else if (op == '/') {
            if (pure_symbol0.equals(pure_symbol1)) {
                newSymbol = "1";
            } else if (pure_symbol0.equals("")) {
                newSymbol = "0";
            } else if (pure_symbol1.equals("")) {
                String msg = String.format("(%s) %s (%s)", pure_symbol0, Character.toString(op), pure_symbol1);
                throw new InvalidSymboicOP("2140", msg);
            } else {
                newSymbol = String.format("D(%s/%s)", pure_symbol0, pure_symbol1);
            }

        } else if (op == '^') {
            if (pure_symbol0.equals(pure_symbol1)) {
                newSymbol = "0";
            } else {
                newSymbol = String.format("D(%s^%s)", pure_symbol0, pure_symbol1);
            }

        } else {
            String msg = String.format("(%s) %s (%s)", pure_symbol0, Character.toString(op), pure_symbol0);
            throw new InvalidSymboicOP("2140", msg);
        }

        return newSymbol;
    }

    /**
     * Binary operation for a pure-symbolic value and an integer value e.g. VRSP +
     * 0x8; VRSP - 0x8; VRSP * 0x8; VRSP / 0x8;
     *
     * @param pure_symbol
     * @param op
     * @param value
     * @return a symbolic value
     */
    private String binaryOP(String pure_symbol, char op, long value) {
        assert (isPureSymbolic(pure_symbol));

        String newSymbol;
        long newValue;

        if (isZero(pure_symbol))
            pure_symbol = "";

        if (pure_symbol.equals("")) {
            if (op == '+') {
                newValue = value;
            } else if (op == '-') {
                newValue = 0 - value;
            } else if (op == '*') {
                newValue = 0;
            } else if (op == '/') {
                newValue = 0;
            } else {
                String msg = String.format("(%s) %s %d", pure_symbol, Character.toString(op), value);
                throw new InvalidSymboicOP("1560", msg);
            }
            newSymbol = String.format("%d", newValue);

        } else if (value == 0) {
            if (op == '+') {
                newSymbol = pure_symbol;
            } else if (op == '-') {
                newSymbol = pure_symbol;
            } else if (op == '*') {
                newSymbol = "0";
            } else {
                String msg = String.format("(%s) %s %d", pure_symbol, Character.toString(op), value);
                throw new InvalidSymboicOP("2140", msg);
            }

        } else {
            if (op == '+') {
                newValue = value;
                newSymbol = String.format("%s %s", pure_symbol, m_digitFmt.format(newValue));
            } else if (op == '-') {
                newValue = 0 - value;
                newSymbol = String.format("%s %s", pure_symbol, m_digitFmt.format(newValue));
            } else if (op == '*') {
                newValue = value;

                if (value == 1) {
                    newSymbol = pure_symbol;
                } else {
                    newSymbol = String.format("D(%s*%d)", pure_symbol, newValue);
                }
            } else if (op == '/') {
                newValue = value;

                if (value == 1) {
                    newSymbol = pure_symbol;
                } else {
                    newSymbol = String.format("D(%s/%s)", pure_symbol, newValue);
                }
            } else if (op == '^') {
                newValue = value;
                newSymbol = String.format("D(%s^%s)", pure_symbol, newValue);
            } else {
                String msg = String.format("(%s) %s %d", pure_symbol, Character.toString(op), value);
                throw new InvalidSymboicValue("2178", msg);
            }
        }

        return newSymbol;
    }

    /**
     * Binary operation for two long values: 0x12 + 0x34; 0x12 - 0x34; 0x12 * 0x34;
     * 0x12 / 0x34; 0x12 ^ 0x34
     *
     * @param value0
     * @param op
     * @param value1
     * @return
     */
    public long binaryOP(long value0, char op, long value1) {
        long res;

        if (op == '+') {
            res = value0 + value1;
        } else if (op == '-') {
            res = value0 - value1;
        } else if (op == '*') {
            res = value0 * value1;
        } else if (op == '/') {
            res = value0 / value1;
        } else if (op == '^') {
            res = value0 ^ value1;
        } else {
            throw new InvalidSymboicOP("2207", Character.toString(op));
        }
        return res;
    }

    public long symbolicBinaryOP(long value0, char op, long value1) {
        return binaryOP(value0, op, value1);
    }

    /**
     * Test if it is symbolic value: which is defined as: 1. starting with
     * (-)[V|D]xxx or 2. a digital value, 3. may contain spaces
     *
     * @param symbol
     * @return
     */
    public boolean isSymbolicValue(String symbol) {
        String[] parts = symbol.split("\\s", 0);

        for (String e : parts) {
            if (!(isPureSymbolic(e) || isPureDigital(e))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Test if it is a pure symbolic value, which is defined as: 1. [V|D]xxx 2.
     * ditigal 0; 3. no space, 4. sign-extended
     *
     * @param symbol
     * @return
     */
    public boolean isPureSymbolic(String symbol) {
        boolean yes;
        int len = symbol.length();

        if (symbol.length() < 1 || symbol.contains(" ")) {
            /* should no spaces */
            yes = false;
        } else if (isZero(symbol)) {
            yes = true;
        } else if ((symbol.charAt(0) == 'V') || (symbol.charAt(0) == 'D')) {
            yes = (symbol.length() > 1);
        } else if (symbol.charAt(0) == '-' && ((symbol.charAt(0) == 'V') || (symbol.charAt(0) == 'D'))) {
            /* sign extend */
            yes = (symbol.length() > 2);
        } else {
            yes = false;
        }

        return yes;
    }

    /**
     * Test if the symbol is zero or not
     *
     * @param symbol
     * @return
     */
    public boolean isZero(String symbol) {
        if (isPureDigital(symbol)) {
            long n = Long.decode(symbol);
            return (n == 0);
        }
        return false;
    }

    /**
     * Test if a symbolic value is pure digitvalue
     *
     * @param symbol
     * @return
     */
    public boolean isPureDigital(String symbol) {
        boolean yes = false;
        try {
            Long.decode(symbol);
            yes = true;
        } catch (Exception e) {

        }
        return yes;
    }
}
