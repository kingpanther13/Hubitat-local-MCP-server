// Experiment-owned validation of the modified JaCoCo integer encoding.
// SHADED_PREFIX is replaced with the downloaded agent's actual relocation prefix.
import SHADED_PREFIX.asm.ClassWriter;
import SHADED_PREFIX.asm.MethodVisitor;
import SHADED_PREFIX.asm.Opcodes;
import SHADED_PREFIX.core.internal.instr.InstrSupport;

public class ProbeEncodingCheck extends ClassLoader implements Opcodes {
    private static void check(int expected) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC, "EncodedInteger", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        method.visitCode();
        InstrSupport.push(method, expected);
        method.visitInsn(IRETURN);
        // Probe insertion reserves 3 stack slots: boolean[] plus at most 2
        // slots to construct the probe index. Let the JVM enforce that bound.
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        Class<?> type = new ProbeEncodingCheck().defineClass("EncodedInteger", bytes, 0, bytes.length);
        int actual = (Integer) type.getMethod("value").invoke(null);
        if (actual != expected) throw new AssertionError(expected + " became " + actual);
    }
    public static void main(String[] args) throws Exception {
        for (int value : new int[] {Integer.MIN_VALUE, -65536, -32769, -32768, -129,
                -128, -2, -1, 0, 5, 6, 127, 128, 32767, 32768, 65535, 65536, Integer.MAX_VALUE}) {
            check(value);
        }
        for (int value = 0; value < 1000000; value += 997) check(value);
        System.err.println("Probe integer encoding and JVM stack bound verified");
    }
}
