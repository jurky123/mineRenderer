"""Compare static storage instructions in spirv-dis output; not a GPU benchmark."""
import argparse
import json
import pathlib
import re


def summarize(path):
    text = pathlib.Path(path).read_text(encoding="utf-8")
    parents = {}
    for line in text.splitlines():
        match = re.match(r"\s*(%\S+) = Op(?:InBounds)?AccessChain %\S+ (%\S+)", line)
        if match:
            parents[match[1]] = match[2]

    def root(pointer):
        while pointer in parents:
            pointer = parents[pointer]
        return pointer

    names = ["pathMedia"] + ["paths" + (str(i) if i else "") for i in range(8)]
    counts = {name: {"load_instructions": 0, "store_instructions": 0} for name in names}
    for line in text.splitlines():
        load = re.match(r"\s*%\S+ = OpLoad %\S+ (%\S+)", line)
        store = re.match(r"\s*OpStore (%\S+) %\S+", line)
        if load or store:
            name = root((load or store)[1]).removeprefix("%")
            if name in counts:
                counts[name]["load_instructions" if load else "store_instructions"] += 1
    return {"source": str(path), "spirv_bytes": len(text.encode()), "storage": counts}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = {"measurement": "static_spirv_instructions", "gpu_acceleration_verified": False,
              "limitations": "Loops and conditional branches affect dynamic instruction counts; driver lowering and cache traffic are not measured.",
              "baseline": summarize(args.baseline), "candidate": summarize(args.candidate)}
    pathlib.Path(args.output).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
