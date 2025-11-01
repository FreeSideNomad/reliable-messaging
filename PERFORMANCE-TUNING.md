# Performance Tuning Guide

This guide covers all performance tuning parameters for the Micronaut reliable messaging application.

## Table of Contents
- [Quick Start](#quick-start)
- [Application-Level Tuning](#application-level-tuning)
- [OS-Level Tuning](#os-level-tuning) **← CRITICAL for 500+ TPS**
- [PostgreSQL Tuning](#postgresql-tuning)
- [Load Testing](#load-testing)
- [Performance Tuning Checklist](#performance-tuning-checklist)
- [Monitoring](#monitoring)
- [Troubleshooting Load Test Failures](#troubleshooting-load-test-failures) **← Fix specific errors**
- [Pre-Flight Checklist Before Load Testing](#pre-flight-checklist-before-load-testing) **← Run before tests**
- [Further Reading](#further-reading)

---

## Quick Start

**CRITICAL WARNING**: For 500+ TPS on macOS, you **MUST** apply OS-level tuning first, or the load test will fail with port exhaustion and file descriptor errors.

### For 500 TPS Target

**Step 1: OS Tuning (REQUIRED - One-time setup)**
```bash
# Apply macOS tuning
./tune-macos.sh

# Make permanent (survives reboots)
./make-tuning-permanent.sh
```

**Step 2: Application Configuration**
```bash
# Edit .env file
NETTY_WORKER_THREADS=200
HIKARI_MAX_POOL_SIZE=100
HIKARI_MIN_IDLE=50
```

**Step 3: Verify and Test**
```bash
# Check all settings
./pre-flight.sh

# Restart application
docker compose down
docker compose up -d

# Run load test
./run-load-test.sh 500 60
```

**For detailed instructions**, see:
- [OS-Level Tuning](#os-level-tuning) - macOS tuning details
- [Troubleshooting Load Test Failures](#troubleshooting-load-test-failures) - Fix specific errors
- [Pre-Flight Checklist](#pre-flight-checklist-before-load-testing) - Automated checks

---

## Application-Level Tuning

### 1. Netty Worker Threads

**What it does**: Handles I/O operations (reading/writing HTTP requests/responses)

**Configuration**: `NETTY_WORKER_THREADS` in `.env`

**Recommended values**:
- **Default formula**: `CPU cores × 2` (e.g., 8 cores = 16 threads)
- **Low load** (< 50 TPS): 50-100 threads
- **Medium load** (50-200 TPS): 100-200 threads
- **High load** (200-500 TPS): 200-400 threads
- **Maximum**: 500 threads (beyond this, context switching overhead hurts performance)

**Why NOT 10,000**:
- Each thread consumes ~1MB stack memory (10,000 = 10GB)
- Excessive context switching degrades performance
- Diminishing returns above 500 threads

**Example**:
```bash
# .env
NETTY_WORKER_THREADS=200
```

---

### 2. Netty Parent Threads

**What it does**: Accepts incoming connections

**Configuration**: `NETTY_PARENT_THREADS` in `.env`

**Recommended values**:
- **Default**: 2-8 threads (sufficient for most cases)
- **High connection rate**: 8-16 threads

**Example**:
```bash
# .env
NETTY_PARENT_THREADS=8
```

---

### 3. HikariCP Connection Pool

**What it does**: Manages database connections

**Configuration**: `HIKARI_MAX_POOL_SIZE` and `HIKARI_MIN_IDLE` in `.env`

**Recommended formula**:
```
connections = (core_count × 2) + effective_spindle_count
```

For typical 8-core machine with SSD:
```
connections = (8 × 2) + 1 = 17
```

**Recommended values**:
- **Low load**: 10-20 connections
- **Medium load**: 20-50 connections
- **High load**: 50-100 connections
- **Maximum**: 100 connections

**Why NOT 10,000**:
- PostgreSQL default max connections: 100-200
- Each connection consumes significant memory on both client and server
- HikariCP creator: "even 100 connections, overkill"
- **More connections ≠ better performance** (often worse due to contention)

**Example**:
```bash
# .env
HIKARI_MAX_POOL_SIZE=100
HIKARI_MIN_IDLE=50
```

---

### 4. Buffer Sizes

**What it does**: Controls memory allocated for HTTP request/response buffers

**Configuration**: In `application.yml` (already configured):
```yaml
micronaut:
  server:
    netty:
      max-initial-line-length: 8192    # HTTP request line
      max-header-size: 16384            # HTTP headers
      max-chunk-size: 16384             # Response chunks
      initial-buffer-size: 256          # Initial buffer
```

**When to adjust**:
- Increase if you have large request/response payloads
- Default values are sufficient for most cases

---

### 5. Timeouts

**Configuration**: In `application.yml` (already configured):
```yaml
micronaut:
  server:
    read-timeout: 30s          # Maximum time to read request
    read-idle-timeout: 60s     # Idle connection timeout
    write-idle-timeout: 60s    # Write idle timeout
    idle-timeout: 60s          # Overall idle timeout
```

**When to adjust**:
- Increase for long-running requests
- Decrease to free up resources faster under high load

---

### 6. HTTP/2 Configuration

**Configuration**: In `application.yml` (already configured):
```yaml
micronaut:
  server:
    netty:
      http2:
        max-concurrent-streams: 100
```

**Benefits**:
- Connection multiplexing (multiple requests over single connection)
- Header compression
- Better performance for concurrent requests

---

## OS-Level Tuning

### macOS

**CRITICAL**: macOS has strict resource limits by default. Without proper OS tuning, you will encounter:
- "bind: can't assign requested address" (port exhaustion)
- "resource temporarily unavailable" (file descriptor limit)
- "connection reset by peer" (socket exhaustion)

#### Pre-Flight Checklist

Run this **BEFORE** every load test to verify your system is ready:

```bash
#!/bin/bash
echo "=== OS Configuration Check ==="
echo ""
echo "1. File Descriptor Limits:"
echo "   Current soft limit: $(ulimit -n)"
echo "   Current hard limit: $(ulimit -Hn)"
echo "   Required: 65536"
if [ $(ulimit -n) -lt 65536 ]; then
  echo "   ❌ FAIL: File descriptor limit too low"
else
  echo "   ✅ PASS"
fi
echo ""

echo "2. Ephemeral Port Range:"
FIRST=$(sysctl -n net.inet.ip.portrange.first)
LAST=$(sysctl -n net.inet.ip.portrange.last)
RANGE=$((LAST - FIRST))
echo "   First port: $FIRST"
echo "   Last port: $LAST"
echo "   Available ports: $RANGE"
echo "   Required: ~49000 ports (range 16384-65535)"
if [ $RANGE -lt 40000 ]; then
  echo "   ❌ FAIL: Port range too narrow"
else
  echo "   ✅ PASS"
fi
echo ""

echo "3. TCP Connection Limits:"
echo "   Max socket buffer: $(sysctl -n kern.ipc.maxsockbuf)"
echo "   Required: 8388608"
if [ $(sysctl -n kern.ipc.maxsockbuf) -lt 8388608 ]; then
  echo "   ❌ FAIL: Socket buffer too small"
else
  echo "   ✅ PASS"
fi
echo ""

echo "4. Current Network State:"
ESTABLISHED=$(netstat -an | grep ESTABLISHED | wc -l | tr -d ' ')
TIME_WAIT=$(netstat -an | grep TIME_WAIT | wc -l | tr -d ' ')
echo "   ESTABLISHED connections: $ESTABLISHED"
echo "   TIME_WAIT connections: $TIME_WAIT"
echo "   (TIME_WAIT should be low before starting test)"
if [ $TIME_WAIT -gt 5000 ]; then
  echo "   ⚠️  WARNING: High TIME_WAIT count, wait before testing"
fi
echo ""
```

Save this as `check-os-tuning.sh` and run before load tests:
```bash
chmod +x check-os-tuning.sh
./check-os-tuning.sh
```

---

#### 1. File Descriptor Limits (CRITICAL)

**What it does**: Controls maximum number of open files/sockets per process

**Current default on macOS**: Usually 256-10240 (too low for high-load testing)

**Check current limits**:
```bash
# Soft limit (can be increased up to hard limit)
ulimit -n

# Hard limit (maximum allowed)
ulimit -Hn

# System-wide limit
launchctl limit maxfiles
```

**Fix: Increase to 65536 for current session**:
```bash
ulimit -n 65536
```

**Fix: Make permanent** (choose ONE method):

**Method A: Add to shell profile** (simpler, per-user):
```bash
# For zsh (macOS default)
echo 'ulimit -n 65536' >> ~/.zshrc
source ~/.zshrc

# For bash
echo 'ulimit -n 65536' >> ~/.bash_profile
source ~/.bash_profile
```

**Method B: System-wide limit** (requires restart):
```bash
# Create/edit launchd config
sudo nano /Library/LaunchDaemons/limit.maxfiles.plist
```

Add this content:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
  <dict>
    <key>Label</key>
    <string>limit.maxfiles</string>
    <key>ProgramArguments</key>
    <array>
      <string>launchctl</string>
      <string>limit</string>
      <string>maxfiles</string>
      <string>65536</string>
      <string>200000</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>ServiceIPC</key>
    <false/>
  </dict>
</plist>
```

Then:
```bash
sudo chown root:wheel /Library/LaunchDaemons/limit.maxfiles.plist
sudo launchctl load -w /Library/LaunchDaemons/limit.maxfiles.plist
```

**Verify the fix**:
```bash
# Should show 65536
ulimit -n

# Open new terminal and verify it persists
ulimit -n
```

---

#### 2. Ephemeral Port Range (CRITICAL)

**What it does**: Controls the range of ports available for outbound connections

**Default on macOS**: Usually 49152-65535 (~16,000 ports) - too narrow for 500 TPS

**Why it matters**:
- Each HTTP connection from load test uses one ephemeral port
- 500 TPS × 60 seconds = 30,000 connections
- Ports stay in TIME_WAIT for 15-120 seconds after closing
- Need ~50,000 available ports for sustained high load

**Check current range**:
```bash
sysctl net.inet.ip.portrange.first
sysctl net.inet.ip.portrange.last

# Calculate available ports
echo "Available ports: $(($(sysctl -n net.inet.ip.portrange.last) - $(sysctl -n net.inet.ip.portrange.first)))"
```

**Fix: Increase ephemeral port range**:
```bash
# Expand range to 16384-65535 (~49,000 ports)
sudo sysctl -w net.inet.ip.portrange.first=16384
sudo sysctl -w net.inet.ip.portrange.last=65535

# Also increase high port range (used by some apps)
sudo sysctl -w net.inet.ip.portrange.hifirst=16384
sudo sysctl -w net.inet.ip.portrange.hilast=65535
```

**Make permanent**:
```bash
# Create/edit sysctl config
sudo nano /etc/sysctl.conf
```

Add these lines:
```
net.inet.ip.portrange.first=16384
net.inet.ip.portrange.last=65535
net.inet.ip.portrange.hifirst=16384
net.inet.ip.portrange.hilast=65535
```

Save and apply:
```bash
sudo sysctl -p
```

**Verify the fix**:
```bash
sysctl net.inet.ip.portrange.first  # Should show 16384
sysctl net.inet.ip.portrange.last   # Should show 65535
echo "Available: $(($(sysctl -n net.inet.ip.portrange.last) - $(sysctl -n net.inet.ip.portrange.first))) ports"
```

---

#### 3. Socket Buffer Sizes

**What it does**: Controls buffer sizes for TCP send/receive operations

**Check current values**:
```bash
sysctl kern.ipc.maxsockbuf
sysctl net.inet.tcp.sendspace
sysctl net.inet.tcp.recvspace
```

**Fix: Increase socket buffers**:
```bash
sudo sysctl -w kern.ipc.maxsockbuf=8388608      # 8MB max buffer
sudo sysctl -w net.inet.tcp.sendspace=1048576   # 1MB send buffer
sudo sysctl -w net.inet.tcp.recvspace=1048576   # 1MB receive buffer
```

**Make permanent** (add to `/etc/sysctl.conf`):
```
kern.ipc.maxsockbuf=8388608
net.inet.tcp.sendspace=1048576
net.inet.tcp.recvspace=1048576
```

**Verify the fix**:
```bash
sysctl kern.ipc.maxsockbuf    # Should show 8388608
sysctl net.inet.tcp.sendspace # Should show 1048576
sysctl net.inet.tcp.recvspace # Should show 1048576
```

---

#### 4. TIME_WAIT Recycling

**What it does**: Reduces time sockets stay in TIME_WAIT state

**Why it matters**: Faster port recycling for high connection churn

**Check current value**:
```bash
sysctl net.inet.tcp.msl  # Default is usually 15000 (15 seconds)
```

**Fix: Reduce TIME_WAIT duration**:
```bash
# Reduce to 1 second (aggressive, good for load testing)
sudo sysctl -w net.inet.tcp.msl=1000
```

**Make permanent** (add to `/etc/sysctl.conf`):
```
net.inet.tcp.msl=1000
```

**Verify the fix**:
```bash
sysctl net.inet.tcp.msl  # Should show 1000
```

**Monitor TIME_WAIT connections**:
```bash
# Watch TIME_WAIT count during load test
watch -n 1 "netstat -an | grep TIME_WAIT | wc -l"
```

---

#### 5. Complete macOS Tuning Script

Save this as `tune-macos.sh` and run once:

```bash
#!/bin/bash

echo "=== Applying macOS Performance Tuning ==="
echo ""

# File descriptors
echo "1. Setting file descriptor limit to 65536..."
ulimit -n 65536
echo "   Current: $(ulimit -n)"

# Ephemeral ports
echo ""
echo "2. Expanding ephemeral port range to 16384-65535..."
sudo sysctl -w net.inet.ip.portrange.first=16384
sudo sysctl -w net.inet.ip.portrange.last=65535
sudo sysctl -w net.inet.ip.portrange.hifirst=16384
sudo sysctl -w net.inet.ip.portrange.hilast=65535
echo "   Range: $(sysctl -n net.inet.ip.portrange.first)-$(sysctl -n net.inet.ip.portrange.last)"

# Socket buffers
echo ""
echo "3. Increasing socket buffer sizes..."
sudo sysctl -w kern.ipc.maxsockbuf=8388608
sudo sysctl -w net.inet.tcp.sendspace=1048576
sudo sysctl -w net.inet.tcp.recvspace=1048576
echo "   Max buffer: $(sysctl -n kern.ipc.maxsockbuf) bytes"

# TIME_WAIT
echo ""
echo "4. Reducing TIME_WAIT duration..."
sudo sysctl -w net.inet.tcp.msl=1000
echo "   MSL: $(sysctl -n net.inet.tcp.msl)ms"

echo ""
echo "=== Tuning Complete ==="
echo ""
echo "To make permanent, run:"
echo "  ./make-tuning-permanent.sh"
```

**Make permanent** with this script (`make-tuning-permanent.sh`):

```bash
#!/bin/bash

echo "Making macOS tuning permanent..."

# Add to shell profile
SHELL_PROFILE=""
if [ -f ~/.zshrc ]; then
  SHELL_PROFILE=~/.zshrc
elif [ -f ~/.bash_profile ]; then
  SHELL_PROFILE=~/.bash_profile
fi

if [ -n "$SHELL_PROFILE" ]; then
  echo "Adding ulimit to $SHELL_PROFILE..."
  echo 'ulimit -n 65536' >> $SHELL_PROFILE
fi

# Create sysctl.conf
echo "Creating /etc/sysctl.conf..."
sudo tee /etc/sysctl.conf > /dev/null <<EOF
# Ephemeral port range (16384-65535 = ~49k ports)
net.inet.ip.portrange.first=16384
net.inet.ip.portrange.last=65535
net.inet.ip.portrange.hifirst=16384
net.inet.ip.portrange.hilast=65535

# Socket buffer sizes
kern.ipc.maxsockbuf=8388608
net.inet.tcp.sendspace=1048576
net.inet.tcp.recvspace=1048576

# TIME_WAIT duration (1 second)
net.inet.tcp.msl=1000
EOF

echo "Done! Restart terminal for changes to take effect."
echo "Or run: source $SHELL_PROFILE"
```

Usage:
```bash
chmod +x tune-macos.sh make-tuning-permanent.sh
./tune-macos.sh
./make-tuning-permanent.sh
```

---

### Linux

#### 1. File Descriptor Limits

Add to `/etc/security/limits.conf`:
```
* soft nofile 65536
* hard nofile 65536
```

#### 2. TCP Tuning

Add to `/etc/sysctl.conf`:
```
# Increase port range
net.ipv4.ip_local_port_range = 10000 65535

# Enable TIME_WAIT recycling
net.ipv4.tcp_tw_reuse = 1

# Increase max connections
net.core.somaxconn = 65535

# Increase socket buffers
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.ipv4.tcp_rmem = 4096 87380 16777216
net.ipv4.tcp_wmem = 4096 65536 16777216

# Increase max backlog
net.core.netdev_max_backlog = 5000
```

Apply changes:
```bash
sudo sysctl -p
```

---

## PostgreSQL Tuning

### 1. Connection Limits

**ALREADY CONFIGURED** in `docker-compose.yml`:
```yaml
command:
  - "postgres"
  - "-c"
  - "max_connections=1000"
```

**Current Settings**:
- `max_connections`: 1000 (up from default 100)
- **Must be higher than sum of all application connection pools**
- Supports up to 10 app instances with 100 connections each

### 2. Memory Settings

**ALREADY CONFIGURED** in `docker-compose.yml`:
```yaml
- "-c"
- "shared_buffers=256MB"
- "-c"
- "effective_cache_size=1GB"
- "-c"
- "maintenance_work_mem=64MB"
- "-c"
- "work_mem=16MB"
```

**Applied Settings**:
- `shared_buffers`: 256MB (25% of RAM)
- `effective_cache_size`: 1GB (50-75% of RAM)
- `maintenance_work_mem`: 64MB
- `work_mem`: 16MB

### 3. Write Performance (Optional - not yet configured)

To further optimize, add to docker-compose.yml command:
```yaml
- "-c"
- "wal_buffers=16MB"
- "-c"
- "checkpoint_completion_target=0.9"
- "-c"
- "max_wal_size=1GB"
- "-c"
- "min_wal_size=256MB"
```

### 4. Connection Pooling (PgBouncer)

For extreme scale (1000+ TPS), consider connection pooler:
```bash
# Install PgBouncer
brew install pgbouncer  # macOS
apt install pgbouncer   # Ubuntu

# Configure for transaction pooling
pool_mode = transaction
max_client_conn = 1000
default_pool_size = 100
```

---

## Load Testing

### Run Load Test

```bash
# 100 TPS for 60 seconds
./run-load-test.sh 100 60

# 500 TPS for 60 seconds
./run-load-test.sh 500 60
```

### Interpreting Results

#### Success Rate
- **100%**: System handling load well
- **95-99%**: Near capacity, minor issues
- **< 95%**: System overloaded

#### Latency (P95)
- **< 50ms**: Excellent
- **50-200ms**: Good
- **200-500ms**: Acceptable
- **> 500ms**: Poor

#### Database Stats
- **Commands SUCCEEDED = Total Requests**: All processed successfully
- **Outbox PUBLISHED = Requests × 3**: All events published
- **DLQ Count = 0**: No errors

---

## Performance Tuning Checklist

**IMPORTANT**: Before running any load test, use the automated pre-flight checklist:
```bash
./pre-flight.sh
```

This will verify all OS, application, and database settings. See [Pre-Flight Checklist](#pre-flight-checklist-before-load-testing) section.

---

### For 100 TPS Target

**Application (.env)**:
- [ ] `NETTY_WORKER_THREADS=100`
- [ ] `HIKARI_MAX_POOL_SIZE=50`
- [ ] `HIKARI_MIN_IDLE=25`

**OS (macOS)**:
- [ ] `ulimit -n 10000`
- [ ] Ephemeral port range: default is OK

**Verify**:
```bash
./check-os-tuning.sh
./run-load-test.sh 100 60
```

---

### For 500 TPS Target (CRITICAL - REQUIRES OS TUNING)

**Application (.env)**:
- [ ] `NETTY_WORKER_THREADS=200`
- [ ] `NETTY_PARENT_THREADS=8`
- [ ] `HIKARI_MAX_POOL_SIZE=100`
- [ ] `HIKARI_MIN_IDLE=50`

**OS (macOS) - CRITICAL**:
- [ ] File descriptors: `ulimit -n 65536`
- [ ] Port range: 16384-65535 (~49k ports)
- [ ] Socket buffers: 8MB max
- [ ] TIME_WAIT: 1 second

**Quick setup**:
```bash
# Run tuning script
./tune-macos.sh

# Make permanent
./make-tuning-permanent.sh

# Verify and run
./pre-flight.sh
./run-load-test.sh 500 60
```

**PostgreSQL** (already configured in docker-compose.yml):
- [x] `max_connections=1000`
- [x] `shared_buffers=256MB`
- [x] `effective_cache_size=1GB`

---

### For 1000+ TPS Target

**Application (.env)**:
- [ ] `NETTY_WORKER_THREADS=400`
- [ ] `HIKARI_MAX_POOL_SIZE=100`

**OS (macOS)**:
- [ ] All 500 TPS OS tuning from above
- [ ] Consider additional tuning based on load test results

**Advanced**:
- [ ] Consider PgBouncer for connection pooling
- [ ] Consider horizontal scaling (multiple instances)
- [ ] Load balancer (nginx, HAProxy)
- [ ] Monitor and tune based on metrics

**Test progressively**:
```bash
# First verify 500 TPS works
./run-load-test.sh 500 60

# Then try 750 TPS
./run-load-test.sh 750 60

# Then 1000 TPS
./run-load-test.sh 1000 60
```

---

## Monitoring

### Application Metrics

Check Micronaut metrics endpoint:
```bash
curl http://localhost:8080/metrics
```

### Database Connections

Check active connections:
```bash
docker exec reliable-postgres psql -U postgres -d reliable -c \
  "SELECT count(*) FROM pg_stat_activity WHERE datname = 'reliable';"
```

### OS-Level Monitoring

**Network connections**:
```bash
# macOS
netstat -an | grep ESTABLISHED | wc -l

# Linux
ss -s
```

**File descriptors**:
```bash
lsof -p <PID> | wc -l
```

---

## Troubleshooting Load Test Failures

This section provides detailed diagnostics and fixes for common load test errors.

---

### Error: "bind: can't assign requested address"

**Full error message**:
```
Post "http://localhost:8080/commands": dial tcp [::1]:8080: socket: too many open files
dial tcp: lookup localhost: no such host
bind: can't assign requested address
```

**Root cause**: Ephemeral port exhaustion

**Why it happens**:
- Each HTTP request from vegeta uses one ephemeral port
- At 500 TPS for 60 seconds, you need ~30,000 ports
- macOS default port range: 49152-65535 (~16,000 ports)
- Ports stay in TIME_WAIT state for 15-120 seconds after closing
- Not enough ports available for sustained load

**Diagnose**:
```bash
# Check current port range
sysctl net.inet.ip.portrange.first
sysctl net.inet.ip.portrange.last

# Calculate available ports
echo "Available: $(($(sysctl -n net.inet.ip.portrange.last) - $(sysctl -n net.inet.ip.portrange.first))) ports"

# Check TIME_WAIT connections (should be low)
netstat -an | grep TIME_WAIT | wc -l

# Check port usage in real-time
while true; do
  echo "$(date) - TIME_WAIT: $(netstat -an | grep TIME_WAIT | wc -l | tr -d ' '), ESTABLISHED: $(netstat -an | grep ESTABLISHED | wc -l | tr -d ' ')"
  sleep 1
done
```

**Fix**:
```bash
# 1. Expand ephemeral port range to ~49k ports
sudo sysctl -w net.inet.ip.portrange.first=16384
sudo sysctl -w net.inet.ip.portrange.last=65535
sudo sysctl -w net.inet.ip.portrange.hifirst=16384
sudo sysctl -w net.inet.ip.portrange.hilast=65535

# 2. Reduce TIME_WAIT duration (faster port recycling)
sudo sysctl -w net.inet.tcp.msl=1000

# 3. Verify the fix
echo "Available ports: $(($(sysctl -n net.inet.ip.portrange.last) - $(sysctl -n net.inet.ip.portrange.first)))"
sysctl net.inet.tcp.msl
```

**Make permanent** (add to `/etc/sysctl.conf`):
```
net.inet.ip.portrange.first=16384
net.inet.ip.portrange.last=65535
net.inet.ip.portrange.hifirst=16384
net.inet.ip.portrange.hilast=65535
net.inet.tcp.msl=1000
```

**Verify after load test**:
```bash
# Should complete without errors
./run-load-test.sh 500 60
```

---

### Error: "resource temporarily unavailable"

**Full error message**:
```
Post "http://localhost:8080/commands": dial tcp [::1]:8080: socket: too many open files
accept: resource temporarily unavailable
```

**Root cause**: File descriptor limit too low

**Why it happens**:
- Each socket connection (both client and server) uses one file descriptor
- macOS default limit: 256-10240 file descriptors per process
- At 500 TPS, you need 10,000+ concurrent connections
- Limit exhausted = no new connections accepted

**Diagnose**:
```bash
# Check current soft limit (adjustable)
ulimit -n

# Check hard limit (max allowed)
ulimit -Hn

# Check system-wide limit
launchctl limit maxfiles

# Monitor file descriptor usage (requires lsof)
# Get PID of your app first
jps | grep Application

# Watch file descriptor usage
watch -n 1 "lsof -p <PID> | wc -l"
```

**Fix (immediate)**:
```bash
# Set to 65536 for current terminal session
ulimit -n 65536

# Verify
ulimit -n  # Should show 65536
```

**Fix (permanent) - Method 1: Shell profile**:
```bash
# For zsh (macOS default)
echo 'ulimit -n 65536' >> ~/.zshrc
source ~/.zshrc

# For bash
echo 'ulimit -n 65536' >> ~/.bash_profile
source ~/.bash_profile

# Open new terminal and verify
ulimit -n  # Should show 65536
```

**Fix (permanent) - Method 2: System-wide (requires restart)**:
```bash
# Create launchd config
sudo nano /Library/LaunchDaemons/limit.maxfiles.plist
```

Add:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
  <dict>
    <key>Label</key>
    <string>limit.maxfiles</string>
    <key>ProgramArguments</key>
    <array>
      <string>launchctl</string>
      <string>limit</string>
      <string>maxfiles</string>
      <string>65536</string>
      <string>200000</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>ServiceIPC</key>
    <false/>
  </dict>
</plist>
```

Then:
```bash
sudo chown root:wheel /Library/LaunchDaemons/limit.maxfiles.plist
sudo launchctl load -w /Library/LaunchDaemons/limit.maxfiles.plist
```

**Verify after fix**:
```bash
# All terminals should now show 65536
ulimit -n

# Run load test
./run-load-test.sh 500 60
```

---

### Error: "connection reset by peer"

**Full error message**:
```
Post "http://localhost:8080/commands": read tcp [::1]:xxxxx->[::1]:8080: read: connection reset by peer
EOF
```

**Root cause**: Multiple possible causes

**Possible causes**:
1. Application overload (thread pool exhausted)
2. Database connection pool exhausted
3. OS socket buffer exhaustion
4. Server closing connections due to resource limits

**Diagnose**:

**1. Check application thread pool**:
```bash
# Check current config
grep NETTY_WORKER_THREADS .env

# During load test, check CPU usage
top -pid <java_pid>
```

**2. Check database connection pool**:
```bash
# Check active connections to PostgreSQL
docker exec reliable-postgres psql -U postgres -d reliable -c \
  "SELECT count(*) as active, max_conn FROM pg_stat_activity,
   (SELECT setting::int as max_conn FROM pg_settings WHERE name='max_connections') mc
   WHERE datname = 'reliable' GROUP BY max_conn;"

# Check HikariCP settings
grep HIKARI .env
```

**3. Check socket buffers**:
```bash
sysctl kern.ipc.maxsockbuf
sysctl net.inet.tcp.sendspace
sysctl net.inet.tcp.recvspace
```

**4. Check application logs**:
```bash
# Look for connection pool exhaustion
docker logs reliable-app 2>&1 | grep -i "pool\|timeout\|exhaust"

# Look for thread pool issues
docker logs reliable-app 2>&1 | grep -i "worker\|thread"
```

**Fix**:

**1. Increase Netty worker threads** (in `.env`):
```bash
NETTY_WORKER_THREADS=200    # For 500 TPS
NETTY_PARENT_THREADS=8
```

**2. Increase HikariCP pool** (in `.env`):
```bash
HIKARI_MAX_POOL_SIZE=100
HIKARI_MIN_IDLE=50
```

**3. Increase socket buffers**:
```bash
sudo sysctl -w kern.ipc.maxsockbuf=8388608
sudo sysctl -w net.inet.tcp.sendspace=1048576
sudo sysctl -w net.inet.tcp.recvspace=1048576
```

**4. Restart application** with new settings:
```bash
docker compose down
docker compose up -d
```

**Verify after fix**:
```bash
# Should complete with minimal errors
./run-load-test.sh 500 60
```

---

### Error: "context deadline exceeded" or timeouts

**Full error message**:
```
Post "http://localhost:8080/commands": context deadline exceeded
i/o timeout
```

**Root cause**: Server taking too long to respond

**Why it happens**:
- Database queries too slow
- Thread pool exhausted (requests queuing)
- Connection pool exhausted (waiting for connections)
- CPU overload

**Diagnose**:

**1. Check response time distribution**:
```bash
# Look at vegeta output for latency breakdown
# P95, P99 should be < 500ms for healthy system
```

**2. Check database query performance**:
```bash
# Enable slow query logging in PostgreSQL
docker exec reliable-postgres psql -U postgres -d reliable -c \
  "ALTER DATABASE reliable SET log_min_duration_statement = 100;"

# Watch slow queries during load test
docker logs -f reliable-postgres 2>&1 | grep "duration:"
```

**3. Check connection pool stats**:
```bash
docker logs reliable-app 2>&1 | grep -i "hikari\|pool"
```

**4. Check CPU usage**:
```bash
# Should not be at 100% continuously
docker stats reliable-app
```

**Fix**:

**1. Optimize database** (if queries are slow):
```sql
-- Add indexes to frequently queried columns
CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox_events(status);
CREATE INDEX IF NOT EXISTS idx_outbox_created ON outbox_events(created_at);

-- Analyze tables for better query plans
ANALYZE outbox_events;
ANALYZE commands;
```

**2. Increase connection pool** (if pool exhausted):
```bash
# In .env
HIKARI_MAX_POOL_SIZE=100
HIKARI_MIN_IDLE=50
```

**3. Increase thread pool** (if threads exhausted):
```bash
# In .env
NETTY_WORKER_THREADS=200
```

**4. Tune PostgreSQL** (if database overloaded):
```yaml
# In docker-compose.yml, add to postgres command:
- "-c"
- "shared_buffers=512MB"      # Increase from 256MB
- "-c"
- "effective_cache_size=2GB"  # Increase from 1GB
- "-c"
- "work_mem=32MB"             # Increase from 16MB
```

**Verify after fix**:
```bash
docker compose down
docker compose up -d

# Wait for startup
sleep 10

# Run load test - P95 latency should be < 500ms
./run-load-test.sh 500 60
```

---

## Pre-Flight Checklist Before Load Testing

Run this checklist **BEFORE EVERY** load test to ensure success:

### 1. OS Configuration Check

Create and run `check-os-tuning.sh`:
```bash
#!/bin/bash
echo "=== OS Configuration Check ==="
echo ""

# File descriptors
FD_LIMIT=$(ulimit -n)
echo "1. File Descriptors: $FD_LIMIT"
if [ $FD_LIMIT -lt 65536 ]; then
  echo "   ❌ FAIL: Run 'ulimit -n 65536' (need 65536)"
  exit 1
else
  echo "   ✅ PASS"
fi

# Port range
FIRST=$(sysctl -n net.inet.ip.portrange.first)
LAST=$(sysctl -n net.inet.ip.portrange.last)
RANGE=$((LAST - FIRST))
echo ""
echo "2. Ephemeral Ports: $FIRST-$LAST ($RANGE ports)"
if [ $RANGE -lt 40000 ]; then
  echo "   ❌ FAIL: Run tune-macos.sh (need ~49k ports)"
  exit 1
else
  echo "   ✅ PASS"
fi

# Socket buffers
MAXSOCK=$(sysctl -n kern.ipc.maxsockbuf)
echo ""
echo "3. Socket Buffers: $MAXSOCK"
if [ $MAXSOCK -lt 8388608 ]; then
  echo "   ❌ FAIL: Run tune-macos.sh"
  exit 1
else
  echo "   ✅ PASS"
fi

# TIME_WAIT
MSL=$(sysctl -n net.inet.tcp.msl)
echo ""
echo "4. TIME_WAIT Duration: ${MSL}ms"
if [ $MSL -gt 5000 ]; then
  echo "   ⚠️  WARNING: Run tune-macos.sh for faster recycling"
else
  echo "   ✅ PASS"
fi

# Network state
TIME_WAIT=$(netstat -an | grep TIME_WAIT | wc -l | tr -d ' ')
echo ""
echo "5. Current TIME_WAIT: $TIME_WAIT"
if [ $TIME_WAIT -gt 5000 ]; then
  echo "   ⚠️  WARNING: High TIME_WAIT, wait 60s before testing"
else
  echo "   ✅ PASS"
fi

echo ""
echo "=== System Ready for Load Testing ==="
```

Usage:
```bash
chmod +x check-os-tuning.sh
./check-os-tuning.sh
```

---

### 2. Application Configuration Check

```bash
#!/bin/bash
echo "=== Application Configuration Check ==="
echo ""

# Check .env file exists
if [ ! -f .env ]; then
  echo "❌ FAIL: .env file not found"
  exit 1
fi

# Check critical settings
source .env

echo "1. Netty Worker Threads: $NETTY_WORKER_THREADS"
if [ -z "$NETTY_WORKER_THREADS" ] || [ "$NETTY_WORKER_THREADS" -lt 200 ]; then
  echo "   ⚠️  WARNING: Recommend 200 for 500 TPS"
else
  echo "   ✅ PASS"
fi

echo ""
echo "2. HikariCP Pool Size: $HIKARI_MAX_POOL_SIZE"
if [ -z "$HIKARI_MAX_POOL_SIZE" ] || [ "$HIKARI_MAX_POOL_SIZE" -lt 100 ]; then
  echo "   ⚠️  WARNING: Recommend 100 for 500 TPS"
else
  echo "   ✅ PASS"
fi

echo ""
echo "3. Docker containers running:"
RUNNING=$(docker ps --filter "name=reliable" --format "{{.Names}}" | wc -l | tr -d ' ')
if [ "$RUNNING" -lt 2 ]; then
  echo "   ❌ FAIL: Run 'docker compose up -d'"
  exit 1
else
  docker ps --filter "name=reliable" --format "   ✅ {{.Names}}: {{.Status}}"
fi

echo ""
echo "=== Application Ready for Load Testing ==="
```

Save as `check-app-config.sh` and run:
```bash
chmod +x check-app-config.sh
./check-app-config.sh
```

---

### 3. Database Health Check

```bash
#!/bin/bash
echo "=== Database Health Check ==="
echo ""

# Check PostgreSQL is running
if ! docker exec reliable-postgres pg_isready -U postgres > /dev/null 2>&1; then
  echo "❌ FAIL: PostgreSQL not ready"
  exit 1
fi
echo "1. PostgreSQL: ✅ Running"

# Check max connections
MAX_CONN=$(docker exec reliable-postgres psql -U postgres -d reliable -tAc \
  "SELECT setting FROM pg_settings WHERE name='max_connections';")
echo ""
echo "2. Max Connections: $MAX_CONN"
if [ "$MAX_CONN" -lt 200 ]; then
  echo "   ⚠️  WARNING: Recommend 1000 for high load"
else
  echo "   ✅ PASS"
fi

# Check active connections
ACTIVE=$(docker exec reliable-postgres psql -U postgres -d reliable -tAc \
  "SELECT count(*) FROM pg_stat_activity WHERE datname = 'reliable';")
echo ""
echo "3. Active Connections: $ACTIVE / $MAX_CONN"
if [ "$ACTIVE" -gt $((MAX_CONN * 80 / 100)) ]; then
  echo "   ❌ FAIL: Too many connections already"
  exit 1
else
  echo "   ✅ PASS"
fi

# Check tables exist
TABLES=$(docker exec reliable-postgres psql -U postgres -d reliable -tAc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';")
echo ""
echo "4. Tables: $TABLES"
if [ "$TABLES" -lt 2 ]; then
  echo "   ❌ FAIL: Tables not created (run migrations)"
  exit 1
else
  echo "   ✅ PASS"
fi

echo ""
echo "=== Database Ready for Load Testing ==="
```

Save as `check-db-health.sh` and run:
```bash
chmod +x check-db-health.sh
./check-db-health.sh
```

---

### 4. Complete Pre-Flight Script

Combine all checks into one script:

```bash
#!/bin/bash
set -e

echo "========================================="
echo "    Load Test Pre-Flight Checklist"
echo "========================================="
echo ""

# Run all checks
./check-os-tuning.sh
echo ""
./check-app-config.sh
echo ""
./check-db-health.sh

echo ""
echo "========================================="
echo "   ✅ ALL CHECKS PASSED"
echo "   Ready to run: ./run-load-test.sh 500 60"
echo "========================================="
```

Save as `pre-flight.sh`:
```bash
chmod +x pre-flight.sh
./pre-flight.sh
```

If all checks pass, proceed with load test:
```bash
./run-load-test.sh 500 60
```

---

## Further Reading

- [HikariCP About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [Micronaut HTTP Server Configuration](https://docs.micronaut.io/latest/guide/configurationreference.html)
- [Netty Performance Tuning](https://netty.io/wiki/reference-counted-objects.html)
- [PostgreSQL Performance Tuning](https://wiki.postgresql.org/wiki/Tuning_Your_PostgreSQL_Server)
