package cpu;

//TODO Optimize the banking of registers
public class CPU {

	public static interface IProcessor {
		/**
		 * Execute an operation.
		 * @param pc Program counter for this operation
		 */
		public void execute(int pc);
	}

	/**
	 * Register 0-7 are "low" registers.
	 * Registers 8-15 are "high" registers.
	 * Register 13 is the stack pointer.
	 * Register 14 is the link registers.
	 * Register 15 is the program counter.
	 * 
	 * Byte - 8 bit data
	 * Halfword - 16 bit data
	 * Word - 32 bit data
	 */
	private final int[][] regs =  {
			{ 0 }, //r0
			{ 0 }, //r1
			{ 0 }, //r2
			{ 0 }, //r3
			{ 0 }, //r4
			{ 0 }, //r5
			{ 0 }, //r6
			{ 0 }, //r7
			{ 0, 0 }, //r8, r8_fiq
			{ 0, 0 }, //r9, r9_fiq
			{ 0, 0 }, //r10, r10_fiq
			{ 0, 0 }, //r11, r11_fiq
			{ 0, 0 }, //r12, r12_fiq
			{ 0, 0, 0, 0, 0, 0 }, //r13 (SP) - STACK PNTR, r13_fiq, r13_irq, r13_svc, r13_abt, r13_und
			{ 0, 0, 0, 0, 0, 0 }, //r14 (LR) -   LINK REG, r14_fiq, r14_irq, r14_svc, r14_abt, r14_und
			{ 0 } //r15 (PC) - PROGRAM COUNTER
	};

	private final int[] spsr = { 0, 0, 0, 0, 0 }; // SPSR (Saved Program Status Register - PRIVELEGED ONLY): SPSR_fiq, SPSR_irq, SPSR_svc, SPSR_abt, SPSR_und

	private final ARMProcessor arm;
	private final THUMBProcessor thumb;

	protected final CPSR cpsr; //CPSR (CONDITION CODE FLAGS AND CURRENT MODE BITS)
	
	private int pc;
	private boolean branched;

	public CPU() {
		arm = new ARMProcessor(this);
		thumb = new THUMBProcessor(this);
		cpsr = new CPSR();
	}
	
	/**
	 * Load CPSR from current SPSR register. Should not be called from USER mode.
	 */
	protected void loadCPSR() {
		int index = cpsr.mapSPSRRegister();
		if (index != -1) 
			cpsr.load(spsr[index]);
	}
	
	/**
	 * Get the current SPSR register. If called from USER mode, returns the CPSR.
	 */
	protected int getSPSR() {
		int index = cpsr.mapSPSRRegister();
		return (index != -1) ? spsr[index] : cpsr.save();
	}
	
	/**
	 * Sets the current SPSR register. Should not be called from USER mode.
	 */
	protected void setSPSR(int val) {
		int index = cpsr.mapSPSRRegister();
		if (index != -1) 
			spsr[index] = val;
	}
	
	/**
	 * Modify the flag bits of the current SPSR register. Should not be called from USER mode.
	 */
	protected void modifySPSR(int val) {
		int index = cpsr.mapSPSRRegister();
		if (index != -1) //Force the old flag bits to 0, OR that with new flag bits
			spsr[index] = (spsr[index] & 0x0FFFFFFF) | (val & 0xF0000000);
	}

	/**
	 * Low registers cannot be banked, so no mode checking is done.
	 * 
	 * @param reg Register to access
	 * @return The value in (reg & 0x7)
	 */
	protected int getLowReg(byte reg) {
		return regs[reg & 0x7][0];
	}

	protected void setLowReg(byte reg, int value) {
		regs[reg & 0x7][0] = value;
	}
	
	/**
	 * Read from a high register, mode checking done because of banking.
	 * 
	 * @param reg Register MINUS 8, thus to access register 8, pass in 0
	 * @return The value in ((reg  & 0x7) + 8) (bank)
	 */
	protected int getHighReg(byte reg) {
		return regs[(reg & 0x7) + 0x8][cpsr.mapHighRegister(reg)];
	}
	
	protected void setHighReg(byte reg, int value) {
		regs[(reg & 0x7) + 0x8][cpsr.mapHighRegister(reg)] = value;
	}
	
	protected int getReg(byte reg) {
		reg = (byte) (reg & 0xF);
		return (reg <= 0x7) ? getLowReg(reg) : getHighReg((byte) (reg - 0x8));
	}
	
	protected void setReg(byte reg, int value) {
		reg = (byte) (reg & 0xF);
		if (reg <= 0x7)
			setLowReg(reg, value);
		else
			setHighReg((byte) (reg - 0x8), value);
	}
	
	protected int getPC() {
		return regs[15][0];
	}
	
	/**
	 * @return The link register for the current mode.
	 */
	protected int getLR() {
		return regs[14][cpsr.mapHighRegister((byte) 6)];
	}
	
	protected void setLR(int val) {
		regs[14][cpsr.mapHighRegister((byte) 6)] = val;
	}
	
	/**
	 * @return The stack pointer for the current mode.
	 */
	protected int getSP() {
		return regs[13][cpsr.mapHighRegister((byte) 5)];
	}
	
	protected void setSP(int val) {
		regs[13][cpsr.mapHighRegister((byte) 5)] = val;
	}

	protected int setAddFlags(int op1, int op2) {
		int result = op1 + op2;
		//Carry if unsigned value has Bit 32 SET
		cpsr.carry = ((op1 & 0xffffffffL) + (op2 & 0xffffffffL) > 0xffffffffL);
		//Overflow if two positives result in a negative or two negatives result in a positive
		cpsr.overflow = (op1 >= 0 && op2 >= 0 && result < 0) || (op1 < 0 && op2 < 0 && result >= 0);
		cpsr.negative = (result < 0);
		cpsr.zero = (result == 0);
		return result;
	}

	protected int setSubFlags(int op1, int op2) {
		int result = op1 - op2;
		//Odd, but must be true because a CMP calls this and CS (Carry SET) is unsigned higher or same o.0
		cpsr.carry = ((op1 & 0xffffffffL) >= (op2 & 0xffffffffL));
		//Overflow if two positives result in a negative or two negatives result in a positive
		cpsr.overflow = (op1 >= 0 && op2 <= 0 && result < 0) || (op1 < 0 && op2 > 0 && result >= 0);
		cpsr.negative = (result < 0);
		cpsr.zero = (result == 0);
		return result;
	}

	protected int setAddCarryFlags(int op1, int op2) {
		int result = op1 + op2 + ((cpsr.carry) ? 1 : 0);
		//Carry if unsigned value has Bit 32 SET
		cpsr.carry = ((op1 & 0xffffffffL) + (op2 & 0xffffffffL) + ((cpsr.carry) ? 1 : 0) > 0xffffffffL);
		//Overflow if two positives result in a negative or two negatives result in a positive
		cpsr.overflow = (op1 >= 0 && op2 >= 0 && result < 0) || (op1 < 0 && op2 < 0 && result >= 0);
		cpsr.negative = (result < 0);
		cpsr.zero = (result == 0);
		return result;
	}

	protected int setSubCarryFlags(int op1, int op2) {
		//SBC Rd, Rs (Rd = Rd - Rs - NOT C-bit)
		int result = op1 - op2 - ((cpsr.carry) ? 0 : 1);
		//Unsigned higher or same including carry
		cpsr.carry = ((op1 & 0xffffffffL) - (op2 & 0xffffffffL) - ((cpsr.carry) ? 0 : 1) >= 0);
		//Overflow if two positives result in a negative or two negatives result in a positive
		cpsr.overflow = (op1 >= 0 && op2 <= 0 && result < 0) || (op1 < 0 && op2 > 0 && result >= 0);
		cpsr.negative = (result < 0);
		cpsr.zero = (result == 0);
		return result;
	}
	
	protected void branch(int address) {
		//TODO Update the PC and set branched to true
	}
	
	protected int read32(int address) {
		//TODO
		return 0;
	}
	
	protected void write32(int address, int val) {
		//TODO
	}
	
	protected int read16(int address) {
		//TODO 
		return 0;
	}
	
	protected void write16(int address, int val) {
		//TODO
	}
	
	protected int read8(int address) {
		//TODO
		return 0;
	}
	
	protected void write8(int address, int val) {
		//TODO
	}
	
	protected void softwareInterrupt(byte arg) {
		
	}
	
	protected void undefinedInstr() {
		
	}

	protected byte accessROM(int pc) {
		return 0;
	}

}
