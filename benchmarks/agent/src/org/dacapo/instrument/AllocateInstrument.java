package org.dacapo.instrument;

import java.util.LinkedList;
import java.util.TreeMap;
import java.util.TreeSet;

import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodAdapter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.objectweb.asm.commons.Method;

public class AllocateInstrument extends Instrument {

	// we need to instrument a call to alloc in the constructor, after the super
	// class init but before the other code.  This should call the reportAlloc
	// if the type of this class is the same as the class containing the constructor.
	// if this.getClass() == Class.class then report allocate
	
	// instrument reference changes from
	//   putfield ...,obj,v'            => ...
	// to
	//   dup2     ...,obj,v'            => ...,obj,v',obj,v'
	//   swap     ...,obj,v',obj,v'     => ...,obj,v',v',obj
	//   dup      ...,obj,v',v',obj     => ...,obj,v',v',obj,obj
	//   getfield ...,obj,v',v',obj,obj => ...,obj,v',v',obj,v
	//   invokespecial pointerchangelog(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
	//            ...,obj,v',v',obj,v   => ...,obj,v'
	//   putfield ...,obj,v'            =>
	
	private static final String   INTERNAL_PREFIX             = "$$";
	
	private static final String   LOG_ALLOC_INC               = "allocInc";
	private static final String   LOG_ALLOC_DEC               = "allocDec";
	private static final String   LOG_ALLOC_DONE              = "allocDone";
	private static final String   LOG_ALLOC_REPORT            = "allocReport";
	private static final String   LOG_POINTER_CHANGE          = "logPointerChange";
	
	private static final String   LOG_INTERNAL_ALLOC_INC      = INTERNAL_PREFIX + LOG_ALLOC_INC;
	private static final String   LOG_INTERNAL_ALLOC_DEC      = INTERNAL_PREFIX + LOG_ALLOC_DEC;
	private static final String   LOG_INTERNAL_ALLOC_DONE     = INTERNAL_PREFIX + LOG_ALLOC_DONE;
	private static final String   LOG_INTERNAL_ALLOC_REPORT   = INTERNAL_PREFIX + LOG_ALLOC_REPORT;
	private static final String   LOG_INTERNAL_POINTER_CHANGE = INTERNAL_PREFIX + LOG_POINTER_CHANGE;
	
	private static final String   LOG_INTERNAL_NAME          = "org/dacapo/instrument/Log";

	private static final String   VOID_SIGNATURE             = "()V";
	private static final String   OBJECT_SIGNATURE           = "(Ljava/lang/Object;)V";
	private static final String   POINTER_CHANGE_SIGNATURE   = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";
	
	private static final String JAVA_LANG_CLASS = org.objectweb.asm.Type.getInternalName(Class.class);
	private static final String JAVA_LANG_CONSTRUCTOR = org.objectweb.asm.Type.getInternalName(java.lang.reflect.Constructor.class);
	private static final String NEW_INSTANCE = "newInstance";

	private String  name;
	private String  superName;
	private int     access;
	private boolean done = false;
	private boolean containsAllocate = false;
	private boolean hasConstructor   = false;
	private boolean logPointerChange = false;
	private TreeSet<String> finalFields = new TreeSet<String>();
	
//	private Type    type;
	
	private static final String   INSTRUMENT_PACKAGE        = "org/dacapo/instrument/";
	// normally exclude sun/reflect/
	
	private LinkedList<String> excludePackages = new LinkedList<String>();
	
	private static final Type OBJECT_TYPE = Type.getType(Object.class);
	
	private static class Pair {
		public String type;
		public int    var;
	}
	
	private static class CountLocals extends MethodAdapter {
		int max;
		public CountLocals(int access, MethodVisitor mv) {
			super(mv);
			max = ((access & Opcodes.ACC_STATIC)!=0)?-1:0;
		}
		public int getMaxLocals() {
			super.visitCode();
			return max;
		}
		public void visitVarInsn(int opcode, int var) {
			super.visitVarInsn(opcode, var);
			max = Math.max(max, var);
		}
	};
	
	public AllocateInstrument(ClassVisitor cv, TreeMap<String,Integer> methodToLargestLocal, String excludeList, boolean logPointerChange) {
		super(cv, methodToLargestLocal);
		this.logPointerChange = logPointerChange;
		
		// always add the instrument package to the exclude list
		excludePackages.add(INSTRUMENT_PACKAGE);
		if (excludeList!=null) {
			String[] packageList = excludeList.split(";");
			for(String p: packageList) {
				p = p.replace('.','/');
				if (! p.endsWith("/"))
					p += p+"/";
				excludePackages.add(p);
			}
		}
	}

	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		this.name      = name;
		this.access    = access;
		this.superName = superName;
		super.visit(version, access, name, signature, superName, interfaces);
	}
	
	public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
		if ((access & Opcodes.ACC_FINAL) != 0) {
			finalFields.add(name);
		}
		return super.visitField(access, name, desc, signature, value);
	}
	
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		if (!done && instrument() && instrument(access)) {
			// CountLocals locals = new CountLocals(access, super.visitMethod(access,name,desc,signature,exceptions));
			// int nextLocal = 0; // locals.getMaxLocals()+1;
			// LocalVariablesSorter lvs = new LocalVariablesSorter(access, desc, super.visitMethod(access, name, desc, signature, exceptions));
			
			return new AllocateInstrumentMethod(access, name, desc, signature, exceptions, super.visitMethod(access, name, desc, signature, exceptions), logPointerChange);
		} else
			return super.visitMethod(access,name,desc,signature,exceptions);
	}
	
	public void visitEnd() {
		if (!done && instrument()) {
			done = true;
			try {
				Class k = Log.class;
				
				GeneratorAdapter mg;
				Label start;
				Label end;

				// Call the Log.reportHeap function which will conditionally report the heap statistics.
				// Note: We cannot get the Heap Statistics from JVMTI and we can't perform a JNI call from
				//       the object allocation event callback so the call back sets a flag when we want the
				//       heap states to be reported after a GC.
				// Also note: Even though a GC can be forced there is no reason to expect that the heap would
				//       be at minimal size due to the asynchronous behaviour of finalizers
				mg = new GeneratorAdapter(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, new Method(LOG_INTERNAL_ALLOC_INC, VOID_SIGNATURE), VOID_SIGNATURE, new Type[] {}, this);
				
				start = mg.mark();
				mg.loadArgs();
				mg.invokeStatic(Type.getType(k), Method.getMethod(k.getMethod(LOG_ALLOC_INC)));
				end   = mg.mark();
				mg.returnValue();
				
				mg.catchException(start, end, Type.getType(Throwable.class));
				mg.returnValue();
				
				mg.endMethod();

				mg = new GeneratorAdapter(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, new Method(LOG_INTERNAL_ALLOC_DEC, VOID_SIGNATURE), VOID_SIGNATURE, new Type[] {}, this);
				
				start = mg.mark();
				mg.loadArgs();
				mg.invokeStatic(Type.getType(k), Method.getMethod(k.getMethod(LOG_ALLOC_DEC)));
				end   = mg.mark();
				mg.returnValue();
				
				mg.catchException(start, end, Type.getType(Throwable.class));
				mg.returnValue();
				
				mg.endMethod();

				mg = new GeneratorAdapter(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, new Method(LOG_INTERNAL_ALLOC_REPORT, OBJECT_SIGNATURE), OBJECT_SIGNATURE, new Type[] {}, this);
				
				start = mg.mark();
				mg.loadArgs();
				mg.invokeStatic(Type.getType(k), Method.getMethod(k.getMethod(LOG_ALLOC_REPORT, Object.class)));
				end   = mg.mark();
				mg.returnValue();
				
				mg.catchException(start, end, Type.getType(Throwable.class));
				mg.returnValue();
				
				mg.endMethod();

				mg = new GeneratorAdapter(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, new Method(LOG_INTERNAL_ALLOC_DONE, VOID_SIGNATURE), VOID_SIGNATURE, new Type[] {}, this);
				
				start = mg.mark();
				mg.loadArgs();
				mg.invokeStatic(Type.getType(k), Method.getMethod(k.getMethod(LOG_ALLOC_DONE)));
				end   = mg.mark();
				mg.returnValue();
				
				mg.catchException(start, end, Type.getType(Throwable.class));
				mg.returnValue();
				
				mg.endMethod();

				mg = new GeneratorAdapter(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, new Method(LOG_INTERNAL_POINTER_CHANGE, POINTER_CHANGE_SIGNATURE), POINTER_CHANGE_SIGNATURE, new Type[] {}, this);
				
				start = mg.mark();
				mg.loadArgs();
				mg.invokeStatic(Type.getType(k), Method.getMethod(k.getMethod(LOG_POINTER_CHANGE, Object.class, Object.class, Object.class)));
				end   = mg.mark();
				mg.returnValue();
				
				mg.catchException(start, end, Type.getType(Throwable.class));
				mg.returnValue();
				
				mg.endMethod();
			} catch (NoSuchMethodException nsme) {
				System.err.println("Unable to find "+LOG_INTERNAL_NAME);
				System.err.println("M:"+nsme);
				nsme.printStackTrace();
			}
		}
		
		super.visitEnd();
	}
	
	private boolean instrument() {
		if ((access & Opcodes.ACC_INTERFACE) != 0) return false;
		
		for(String k: excludePackages) {
			if (name.startsWith(k)) return false;
		}
		
		return true;
	}
	
	private boolean instrument(int access) {
		return (access & Opcodes.ACC_ABSTRACT) == 0 && (access & Opcodes.ACC_BRIDGE) == 0 && (access & Opcodes.ACC_NATIVE) == 0;
	}

	private class AllocateInstrumentMethod extends AdviceAdapter {
		private static final String CONSTRUCTOR = "<init>";
		
		boolean constructor;
		boolean firstInstruction;

		private String   encodedName;
		private int      localsBase = 0;
		private int      maxLocals;
		private MethodVisitor mv; 
		private Label    methodStartLabel;
		private String[] exceptions;
		private boolean  methodDone;
		private boolean  logPointerChanges;
		private boolean  doneSuperConstructor;
		private TreeMap<String, String> delayedFieldPointer = new TreeMap<String, String>();
		
		private static final boolean DO_INC_DEC = true;
		private static final boolean DO_NEW_INVOKESPECIAL_SEQUENCE = true;
		
		// LinkedList<String> newTypeStack = new LinkedList<String>();
		LinkedList<Pair> newTypeStack = new LinkedList<Pair>();

		AllocateInstrumentMethod(int access, String methodName, String desc, String signature, String[] exceptions, MethodVisitor mv, boolean logPointerChanges) {
			super(mv, access, methodName, desc);
			this.mv = mv;
			this.constructor = DO_INC_DEC && CONSTRUCTOR.equals(methodName);
			this.firstInstruction = constructor;
			this.methodStartLabel = null;
			this.exceptions = exceptions;
			this.methodDone = false;
			this.doneSuperConstructor = !constructor;
			this.encodedName = Instrument.encodeMethodName(name, methodName, desc);
			if (! methodToLargestLocal.containsKey(this.encodedName))
				methodToLargestLocal.put(this.encodedName, Instrument.getArgumentSizes(access,desc));
			this.localsBase = methodToLargestLocal.get(this.encodedName);
			this.maxLocals = this.localsBase;
		}
		
		public void onMethodExit(int opcode) {
			if (opcode != Opcodes.ATHROW)
				addDec();
		}
		
		public void	visitFieldInsn(int opcode, String owner, String fieldName, String desc) {
			if (firstInstruction)
				addInc();
			if (logPointerChange && opcode == Opcodes.PUTFIELD && desc.charAt(0) == 'L') {
				if (constructor && !doneSuperConstructor && name.equals(owner) && finalFields.contains(fieldName))
					delayedFieldPointer.put(fieldName, desc);
				else {
					// instrument reference changes from
					//   putfield ...,obj,v'            => ...
					// to
					//   dup2     ...,obj,v'            => ...,obj,v',obj,v'
					//   swap     ...,obj,v',obj,v'     => ...,obj,v',v',obj
					//   dup      ...,obj,v',v',obj     => ...,obj,v',v',obj,obj
					//   getfield ...,obj,v',v',obj,obj => ...,obj,v',v',obj,v
					//   invokespecial pointerchangelog(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
					//            ...,obj,v',v',obj,v   => ...,obj,v'
					//   putfield ...,obj,v'            =>
					super.visitInsn(Opcodes.DUP2);
					super.visitInsn(Opcodes.SWAP);
					super.visitInsn(Opcodes.DUP);
					super.visitFieldInsn(Opcodes.GETFIELD,owner,fieldName,desc);
					super.visitMethodInsn(Opcodes.INVOKESTATIC, name, LOG_INTERNAL_POINTER_CHANGE, POINTER_CHANGE_SIGNATURE);
				}
			}
			super.visitFieldInsn(opcode,owner,fieldName,desc);
		} 
		public void	visitInsn(int opcode) {
			if (firstInstruction)
				addInc();
			super.visitInsn(opcode);
		}
		public void	visitJumpInsn(int opcode, Label label) {
			if (firstInstruction)
				addInc();
			super.visitJumpInsn(opcode,label);
		}
		public void	visitLdcInsn(Object cst) {
			if (firstInstruction)
				addInc();
			super.visitLdcInsn(cst);
		}
		public void	visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
			if (firstInstruction)
				addInc();
			super.visitLookupSwitchInsn(dflt,keys,labels);
		}
		public void	visitMethodInsn(int opcode, String owner, String methodName, String desc) {
			boolean reportReflectConstruction = opcode == Opcodes.INVOKEVIRTUAL && NEW_INSTANCE.equals(methodName) && desc.endsWith(";") && 
			   (JAVA_LANG_CLASS.equals(owner) || JAVA_LANG_CONSTRUCTOR.equals(owner));
			if (firstInstruction)
				addInc();
			Label wrapFrom = null;
			if (reportReflectConstruction && !constructor) {
				wrapFrom = super.mark();
				addInc();
			}
			super.visitMethodInsn(opcode,owner,methodName,desc);
			if (opcode == Opcodes.INVOKESPECIAL && CONSTRUCTOR.equals(methodName)) {
				if (DO_NEW_INVOKESPECIAL_SEQUENCE && !newTypeStack.isEmpty()) {
					Pair p = newTypeStack.removeLast();
					if (! p.type.equals(owner)) {
						System.err.println("Excepted type: "+p.type+" found: "+owner);
						System.exit(10);
					}
					super.visitVarInsn(Opcodes.ALOAD,p.var);
					addLog(false);
				}
				if (superName.equals(owner)) {
					doneSuperConstructor = true;
					// now log all the pointer changes for final fields
					for(String fieldName: delayedFieldPointer.keySet()) {
						// aload_0
						// getfield
						// aload_0
						// aconst_null
						// invoke
						super.visitVarInsn(Opcodes.ALOAD,0);
						super.visitFieldInsn(Opcodes.GETFIELD,name,fieldName,delayedFieldPointer.get(fieldName));
						super.visitVarInsn(Opcodes.ALOAD,0);
						super.visitInsn(Opcodes.ACONST_NULL);
						super.visitMethodInsn(Opcodes.INVOKESTATIC, name, LOG_INTERNAL_POINTER_CHANGE, POINTER_CHANGE_SIGNATURE);
					}
				}
			} else if (reportReflectConstruction) {
				if (!constructor) {
					Type throwType = Type.getType(Throwable.class);
					Label wrapTo = super.mark();
					addDec();
					addLog(true);
					Label target = super.newLabel();
					super.visitJumpInsn(Opcodes.GOTO,target);
					super.catchException(wrapFrom,wrapTo,throwType);
					addDec();
					super.visitMethodInsn(Opcodes.INVOKESTATIC, name, LOG_INTERNAL_ALLOC_DONE, VOID_SIGNATURE);
					super.visitInsn(Opcodes.ATHROW);
					super.mark(target);
				} else {
					addLog(true);
				}
			}
		} 
		public void	visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels) {
			if (firstInstruction)
				addInc();
			super.visitTableSwitchInsn(min,max,dflt,labels);
		} 
		public void visitVarInsn(int opcode, int var) {
			if (firstInstruction)
				addInc();
			super.visitVarInsn(opcode,var);
		}
		public void visitMultiANewArrayInsn(String desc, int dims) {
			if (firstInstruction)
				addInc();
			super.visitMultiANewArrayInsn(desc, dims);
			addLog(true);
		}
		public void visitTypeInsn(int opcode, String type) {
			if (firstInstruction)
				addInc();
			super.visitTypeInsn(opcode, type);
			// we deal with Opcodes.NEW through the constructors
			if (opcode == Opcodes.ANEWARRAY)
				addLog(true);
			else if (DO_NEW_INVOKESPECIAL_SEQUENCE && opcode == Opcodes.NEW) {
				super.visitInsn(Opcodes.DUP);
				Pair p = new Pair();
				p.type = type;
				p.var  = this.localsBase + 1 + newTypeStack.size();
				if (this.maxLocals < p.var) {
					this.maxLocals = p.var;
					methodToLargestLocal.put(this.encodedName, new Integer(p.var));
				}
				super.setLocalType(p.var,OBJECT_TYPE);	// super.newLocal(OBJECT_TYPE);
				newTypeStack.addLast(p);
				super.visitVarInsn(Opcodes.ASTORE,p.var);
			}
		}
		public void visitIntInsn(int opcode, int operand) {
			if (firstInstruction)
				addInc();
			super.visitIntInsn(opcode, operand);
			if (opcode == Opcodes.NEWARRAY)
				addLog(true);
		}
		
		public void visitEnd() {
			if (!methodDone && methodStartLabel != null && constructor) {
				methodDone = true;
				Label methodEndLabel = super.mark();
				super.catchException(methodStartLabel,methodEndLabel,Type.getType(RuntimeException.class));
				super.visitMethodInsn(Opcodes.INVOKESTATIC, name, LOG_INTERNAL_ALLOC_DEC, VOID_SIGNATURE);
				super.visitInsn(Opcodes.ATHROW);
				if (exceptions!=null) {
					for(String ex: exceptions) {
						super.catchException(methodStartLabel,methodEndLabel,Type.getObjectType(ex));
						super.visitMethodInsn(Opcodes.INVOKESTATIC, name, LOG_INTERNAL_ALLOC_DEC, VOID_SIGNATURE);
						super.visitInsn(Opcodes.ATHROW);
					}
				}
			}
			super.visitEnd();
		}
		
		private void addInc() {
			if (constructor) {
				super.visitMethodInsn(Opcodes.INVOKESTATIC, name, LOG_INTERNAL_ALLOC_INC, VOID_SIGNATURE);
				methodStartLabel = super.mark();
			}
			firstInstruction = false;
		}
		private void addDec() {
			if (constructor)
				super.visitMethodInsn(Opcodes.INVOKESTATIC, name, LOG_INTERNAL_ALLOC_DEC, VOID_SIGNATURE);
		}
		private void addLog(boolean dup) {
			if (dup)
				super.visitInsn(Opcodes.DUP);
			super.visitMethodInsn(Opcodes.INVOKESTATIC, name, LOG_INTERNAL_ALLOC_REPORT, OBJECT_SIGNATURE);
			if (! constructor)
				super.visitMethodInsn(Opcodes.INVOKESTATIC, name, LOG_INTERNAL_ALLOC_DONE, VOID_SIGNATURE);
		}
		
		private void addWhereAmI() {
//			   0:   new     #2; //class java/lang/Exception
//		   3:   dup
//		   4:   invokespecial   #3; //Method java/lang/Exception."<init>":()V
//		   7:   invokevirtual   #4; //Method java/lang/Exception.printStackTrace:()V
//		   10:  return
			// super.visitTypeInsn(Opcodes.NEW, type);
			String exClass = Type.getInternalName(Exception.class);
			super.visitTypeInsn(Opcodes.NEW, exClass);
			super.visitInsn(Opcodes.DUP);
			super.visitMethodInsn(Opcodes.INVOKESPECIAL, exClass, "<init>", "()V");
			super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, exClass, "printStackTrace","()V");
		}
	}
}