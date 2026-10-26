# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Small CPU-only checks; no Minecraft launch or GPU profiling."""
import argparse,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--java-home',type=Path,required=True);p.add_argument('--classpath-file',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parent
out=root/'build/test-classes';out.mkdir(parents=True,exist_ok=True)
cp=str(root/'build/classes')+';'+a.classpath_file.read_text().strip()
sources=[str(s) for s in (root/'src/test/java').rglob('*.java')]
subprocess.run([str(a.java_home/'bin/javac.exe'),'--release','25','-proc:none','-cp',cp,'-d',str(out),*sources],check=True)
r=subprocess.run([str(a.java_home/'bin/java.exe'),'-ea','-cp',str(out)+';'+cp,'com.nvidia.rtxremix.port.PortModuleChecks'],text=True,capture_output=True,encoding='utf-8',errors='replace')
(root/'build/module-checks.log').write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr);raise SystemExit(r.returncode)
