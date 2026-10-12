"""Avoid large probe-index constants overflowing the server's JVM constant pool.

Upstream: jacoco/jacoco v0.8.15 InstrSupport.java (EPL-2.0, header retained).
Only integer encoding changes; probe placement and identity remain upstream.
Run on the CI runner, never against the hub.
"""
import pathlib
import re
import subprocess
import urllib.request
import zipfile

root = pathlib.Path(__file__).resolve().parent
out = root / 'agent-build'
out.mkdir(exist_ok=True)
archive = out / 'original.jar'
urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/org/jacoco/org.jacoco.agent/0.8.15/org.jacoco.agent-0.8.15-runtime.jar', archive)
with zipfile.ZipFile(archive) as jar:
    name, = [n for n in jar.namelist() if n.endswith('/core/internal/instr/InstrSupport.class')]
prefix = name.removesuffix('/core/internal/instr/InstrSupport.class').replace('/', '.')
source = (root / 'vendor/InstrSupport.java').read_text()
assert source.count('mv.visitLdcInsn(Integer.valueOf(value));') == 1
source = source.replace('mv.visitLdcInsn(Integer.valueOf(value));', '''
            // Same integer, encoded using immediate operands instead of a
            // distinct CONSTANT_Integer entry for every large probe index.
            mv.visitIntInsn(Opcodes.SIPUSH, value >> 16);
            mv.visitIntInsn(Opcodes.BIPUSH, 16);
            mv.visitInsn(Opcodes.ISHL);
            mv.visitIntInsn(Opcodes.SIPUSH, (value >>> 8) & 255);
            mv.visitIntInsn(Opcodes.BIPUSH, 8);
            mv.visitInsn(Opcodes.ISHL);
            mv.visitInsn(Opcodes.IOR);
            mv.visitIntInsn(Opcodes.SIPUSH, value & 255);
            mv.visitInsn(Opcodes.IOR);''')
source = source.replace('org.jacoco.core', prefix + '.core').replace('org.objectweb.asm', prefix + '.asm')
patched_source = out / 'InstrSupport.java'
patched_source.write_text(source)
subprocess.run(['javac', '--release', '8', '-cp', str(archive), '-d', str(out), str(patched_source)], check=True)
agent = out / 'jacocoagent.jar'
with zipfile.ZipFile(archive) as original, zipfile.ZipFile(agent, 'w', zipfile.ZIP_DEFLATED) as patched:
    for info in original.infolist():
        patched.writestr(info, (out / name).read_bytes() if info.filename == name else original.read(info.filename))
print(agent)
