# OptiX startup compilation — 0.37.5

0.37.4 remained in module compilation for at least 3m51s in the user's new log.
No completion or dispatch failure is present in that excerpt. Reducing PTX size
alone has not established an acceptable startup time.

0.37.5 replaces synchronous `optixModuleCreate` with `optixModuleCreateWithTasks`.
A bounded pool executes tasks returned by `optixTaskExecute`, including newly
returned dependent tasks. It uses at most four workers and reserves two logical
CPUs where available. Workers set the CUDA context before executing a task.
Input PTX, compiler log and module remain alive until every worker has joined.
On failure, queued work is abandoned, running work finishes, then the error is
propagated before module destruction. A module must report COMPLETED before
program groups or pipeline creation can proceed. No partially compiled module
can become active.

The module now uses `OPTIX_COMPILE_OPTIMIZATION_LEVEL_0` rather than level 2.
This requests no optimizer pass. It preserves material equations, transport,
NEE/MIS, bounce counts and sample budgets, but can reduce GPU execution speed.
This release prioritizes making the compiler finish in a usable time; no startup
or runtime speedup is claimed without NVIDIA measurements. The non-inlined
transport functions from 0.37.4 remain.

Status and logs report completed/discovered compiler tasks, optimization level
and elapsed time every 15 seconds. Discovered tasks can increase, so this is not
a fixed percentage. Background startup continues to retain raster rendering.
The standard OptiX disk-cache settings remain authoritative, including NVIDIA's
OPTIX_CACHE_PATH/OPTIX_CACHE_MAXSIZE environment overrides. No compiled artifact
is reused across unsupported device/driver/compiler keys by VoxelLight.

The task API and concurrency/lifetime rules come from the pinned OptiX 9.1 SDK
`optix_host.h`; see the [NVIDIA OptiX documentation](https://raytracing-docs.nvidia.com/optix9/guide/index.html).

## In-game check

Install the complete matching 0.37.5 native kit. Enable reference and compare:

- first native compilation duration;
- task counts across successive 15-second log entries;
- whether compilation reaches `RTX initialization complete`;
- whether `rtReferenceSamples` increases with a stationary camera;
- GPU frame time after startup, and restart/cache behavior.

If module compilation remains long, send the final few RTX heartbeat lines. If
startup completes but reference runs too slowly, report its sample progress and
FPS separately. Neither is an account-authentication problem.

Host tests exercise a task dependency tree for exactly-once execution, an error
while other tasks are running, join-before-error propagation and an empty cache-hit
workload. Windows/Linux native builds verify the actual SDK calls. This host has
no NVIDIA GPU/display; first-start compiler time and reference execution still
require in-game acceptance.
