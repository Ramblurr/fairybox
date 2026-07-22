#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
launcher="$project_root/scripts/fairybox-launch"
deployer="$project_root/scripts/fairybox-deploy"
temporary_root=$(mktemp -d)
trap 'rm -rf "$temporary_root"' EXIT

fail() {
  printf 'deployment integration test: %s\n' "$*" >&2
  exit 1
}

assert_equal() {
  [[ "$1" == "$2" ]] || fail "expected '$1' to equal '$2'"
}

write_executable() {
  local path=$1
  cat > "$path"
  chmod 700 "$path"
}

launcher_scenarios() {
  local fixture="$temporary_root/launcher"
  local root="$fixture/root"
  local fake_authbind="$fixture/authbind"
  local arguments="$fixture/arguments"
  local release_sha
  local release
  local cache_sha
  release_sha=$(printf jar | sha256sum | awk '{print $1}')
  release="$root/releases/$release_sha"
  mkdir -p "$release"
  printf jar > "$release/box-standalone.jar"
  printf cache > "$release/box-standalone.aot"
  cache_sha=$(sha256sum "$release/box-standalone.aot" | awk '{print $1}')
  printf 'JAR_SHA256=%s\nCACHE_SHA256=%s\nJAVA_FINGERPRINT=%064d\nARCHITECTURE=aarch64\n' \
    "$release_sha" "$cache_sha" 1 > "$release/release.env"
  printf complete > "$release/.complete"
  ln -s "releases/$release_sha" "$root/current"
  write_executable "$fake_authbind" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$FAIRYBOX_TEST_ARGUMENTS"
EOF

  FAIRYBOX_ROOT="$root" \
  FAIRYBOX_AUTHBIND="$fake_authbind" \
  FAIRYBOX_JAVA="$fixture/java" \
  FAIRYBOX_TEST_ARGUMENTS="$arguments" \
    "$launcher" >/dev/null 2> "$fixture/complete.log"
  grep -Fxq -- -XX:AOTMode=auto "$arguments"
  grep -Fxq -- "-XX:AOTCache=$release/box-standalone.aot" "$arguments"
  grep -Fxq -- -Xlog:aot=info "$arguments"
  grep -Fxq -- "$release/box-standalone.jar" "$arguments"
  if grep -Fxq -- -XX:+UseZGC "$arguments"; then
    fail "launcher enabled ZGC, which disables AOT-linked classes on the target JDK"
  fi
  grep -Fxq -- --enable-native-access=ALL-UNNAMED "$arguments"
  grep -Fxq -- -Dclojure.tools.logging.factory=clojure.tools.logging.impl/jul-factory "$arguments"

  rm -- "$release/box-standalone.aot"
  if FAIRYBOX_ROOT="$root" \
    FAIRYBOX_AUTHBIND="$fake_authbind" \
    FAIRYBOX_JAVA="$fixture/java" \
    FAIRYBOX_TEST_ARGUMENTS="$arguments" \
      "$launcher" >/dev/null 2> "$fixture/incomplete.log"
  then
    fail "launcher accepted a release without an AOT cache"
  fi
  grep -Fq 'AOT cache is missing or unreadable' "$fixture/incomplete.log"

  rm -- "$root/current"
  printf legacy > "$root/box-standalone.jar"
  if FAIRYBOX_ROOT="$root" \
    FAIRYBOX_AUTHBIND="$fake_authbind" \
    FAIRYBOX_JAVA="$fixture/java" \
    FAIRYBOX_TEST_ARGUMENTS="$arguments" \
      "$launcher" >/dev/null 2> "$fixture/missing-current.log"
  then
    fail "launcher accepted the old single-jar layout"
  fi
  grep -Fq 'managed current release symlink is missing' "$fixture/missing-current.log"
}

write_deploy_fakes() {
  local bin=$1
  mkdir -p "$bin"

  write_executable "$bin/jar" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' \
  fairy/box/core.class \
  com/aayushatharva/brotli4j/linux/aarch64/NativeLoader.class \
  lib/linux-aarch64/libbrotli.so
EOF

  write_executable "$bin/java" <<'EOF'
#!/usr/bin/env bash
if [[ "$*" == *PrintFlagsFinal* ]]; then
  printf 'AOTMode\nAOTCacheOutput\n' >&2
else
  printf 'fake-jdk-25\n' >&2
fi
EOF

  write_executable "$bin/authbind" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF

  write_executable "$bin/systemctl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == --user ]] && shift
command=$1
shift
printf '%s\t%s\n' "$command" "$*" >> "$FAIRYBOX_TEST_STATE/calls"
case "$command" in
  is-active)
    [[ "${1:-}" == --quiet ]] && shift
    unit=$1
    if [[ "$unit" == fairybox.service ]]; then
      state_file="$FAIRYBOX_TEST_STATE/service"
    else
      state_file="$FAIRYBOX_TEST_STATE/training"
    fi
    [[ $(cat "$state_file") == active ]]
    ;;
  stop)
    unit=$1
    if [[ "$unit" == fairybox.service ]]; then
      printf failed > "$FAIRYBOX_TEST_STATE/service"
    else
      printf failed > "$FAIRYBOX_TEST_STATE/training"
    fi
    ;;
  start)
    printf active > "$FAIRYBOX_TEST_STATE/service"
    ;;
  reset-failed)
    unit=$1
    if [[ "$unit" == fairybox.service ]]; then
      printf inactive > "$FAIRYBOX_TEST_STATE/service"
    else
      printf inactive > "$FAIRYBOX_TEST_STATE/training"
    fi
    ;;
  show)
    property=
    for argument in "$@"; do
      case "$argument" in
        MainPID|ExecMainStatus) property=$argument ;;
      esac
    done
    if [[ "$property" == ExecMainStatus ]]; then
      printf '143\n'
    else
      printf '0\n'
    fi
    ;;
  *)
    printf 'unexpected systemctl command: %s\n' "$command" >&2
    exit 2
    ;;
esac
EOF

  write_executable "$bin/systemd-run" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cache=
log=
jar=
printf '%s\n' "$@" > "$FAIRYBOX_TEST_STATE/training-arguments"
for argument in "$@"; do
  case "$argument" in
    -XX:AOTCacheOutput=*) cache=${argument#*=} ;;
    --property=StandardOutput=append:*) log=${argument#*append:} ;;
    *.jar) jar=$argument ;;
  esac
done
[[ -n "$cache" && -n "$log" && -n "$jar" ]]
printf cache > "$cache"
printf 'AOTCache creation is complete: %s\n' "$cache" > "$log"
printf '%s' "$jar" > "$FAIRYBOX_TEST_STATE/trained-jar"
printf active > "$FAIRYBOX_TEST_STATE/training"
EOF

  write_executable "$bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == *--compressed* ]]
[[ "$*" == *"Accept-Encoding: br"* ]]
count=$(cat "$FAIRYBOX_TEST_STATE/curl-count")
count=$((count + 1))
printf '%s' "$count" > "$FAIRYBOX_TEST_STATE/curl-count"
if [[ "$FAIRYBOX_TEST_CURL_MODE" == training-then-fail && "$count" -gt 1 ]]; then
  printf '{"system-state":"warming-up"}\n503'
else
  printf '{"system-state":"ready"}\n200'
fi
EOF

  write_executable "$bin/journalctl" <<'EOF'
#!/usr/bin/env bash
release=$(readlink -f -- "$FAIRYBOX_ROOT/current")
printf 'Opened AOT cache %s/box-standalone.aot.\n' "$release"
printf 'Using AOT-linked classes: true (static archive: has aot-linked classes)\n'
EOF
}

setup_deploy_fixture() {
  local name=$1
  local service_state=$2
  local fixture="$temporary_root/$name"
  local root="$fixture/root"
  local state="$fixture/state"
  local bin="$fixture/bin"
  local old_sha
  local old_release
  local old_cache_sha
  mkdir -p "$root/incoming" "$state"
  old_sha=$(printf old | sha256sum | awk '{print $1}')
  old_release="$root/releases/$old_sha"
  mkdir -p "$old_release"
  printf old > "$old_release/box-standalone.jar"
  printf old-cache > "$old_release/box-standalone.aot"
  old_cache_sha=$(sha256sum "$old_release/box-standalone.aot" | awk '{print $1}')
  printf 'JAR_SHA256=%s\nCACHE_SHA256=%s\nJAVA_FINGERPRINT=%064d\nARCHITECTURE=aarch64\n' \
    "$old_sha" "$old_cache_sha" 1 > "$old_release/release.env"
  printf complete > "$old_release/.complete"
  ln -s "releases/$old_sha" "$root/current"
  printf '%s' "$old_sha" > "$state/old-sha"
  printf 'secret=x\n' > "$root/secret-env"
  printf '%s' "$service_state" > "$state/service"
  printf inactive > "$state/training"
  printf 0 > "$state/curl-count"
  : > "$state/calls"
  write_deploy_fakes "$bin"
  printf '%s\n' "$fixture"
}

run_deploy() {
  local fixture=$1
  local mode=$2
  local sha=$3
  shift 3
  FAIRYBOX_ROOT="$fixture/root" \
  FAIRYBOX_SYSTEMCTL="$fixture/bin/systemctl" \
  FAIRYBOX_SYSTEMD_RUN="$fixture/bin/systemd-run" \
  FAIRYBOX_JOURNALCTL="$fixture/bin/journalctl" \
  FAIRYBOX_CURL="$fixture/bin/curl" \
  FAIRYBOX_JAR="$fixture/bin/jar" \
  FAIRYBOX_JAVA="$fixture/bin/java" \
  FAIRYBOX_AUTHBIND="$fixture/bin/authbind" \
  FAIRYBOX_MINIMUM_FREE_KIB=1 \
  FAIRYBOX_READY_TIMEOUT=1 \
  FAIRYBOX_POLL_INTERVAL=0.05 \
  FAIRYBOX_ARCHITECTURE=aarch64 \
  FAIRYBOX_TEST_STATE="$fixture/state" \
  FAIRYBOX_TEST_CURL_MODE="$mode" \
    "$deployer" install "$sha" "$@"
}

stage_release() {
  local fixture=$1
  local contents=$2
  local source="$fixture/new.jar"
  printf '%s' "$contents" > "$source"
  local sha
  sha=$(sha256sum "$source" | awk '{print $1}')
  mkdir -p "$fixture/root/incoming/$sha"
  cp -- "$source" "$fixture/root/incoming/$sha/box-standalone.jar.part"
  printf '%s\n' "$sha"
}

successful_active_deploy() {
  local fixture
  local sha
  local old_sha
  fixture=$(setup_deploy_fixture successful-active active)
  sha=$(stage_release "$fixture" new-active)
  run_deploy "$fixture" ready "$sha" >/dev/null
  old_sha=$(cat "$fixture/state/old-sha")

  assert_equal "$(readlink -f -- "$fixture/root/current")" \
    "$fixture/root/releases/$sha"
  assert_equal "$(readlink -f -- "$fixture/root/previous")" \
    "$fixture/root/releases/$old_sha"
  assert_equal "$(cat "$fixture/state/service")" active
  assert_equal "$(cat "$fixture/state/trained-jar")" \
    "$fixture/root/releases/$sha/box-standalone.jar"
  if grep -Fxq -- -XX:+UseZGC "$fixture/state/training-arguments"; then
    fail "training enabled ZGC, which disables AOT-linked classes on the target JDK"
  fi
  grep -Fxq -- --enable-native-access=ALL-UNNAMED "$fixture/state/training-arguments"
  grep -Fxq -- -Dclojure.tools.logging.factory=clojure.tools.logging.impl/jul-factory \
    "$fixture/state/training-arguments"
  [[ -s "$fixture/root/releases/$sha/box-standalone.aot" ]]
  [[ -f "$fixture/root/releases/$sha/release.env" ]]
  [[ -f "$fixture/root/releases/$sha/.complete" ]]
  if grep -Eq '^enable|^disable' "$fixture/state/calls"; then
    fail "deployment changed service enablement"
  fi
}

successful_inactive_deploy() {
  local fixture
  local sha
  fixture=$(setup_deploy_fixture successful-inactive inactive)
  sha=$(stage_release "$fixture" new-inactive)
  run_deploy "$fixture" ready "$sha" >/dev/null
  assert_equal "$(readlink -f -- "$fixture/root/current")" \
    "$fixture/root/releases/$sha"
  assert_equal "$(cat "$fixture/state/service")" inactive
}

checksum_failure_precedes_downtime() {
  local fixture
  local bad_sha
  fixture=$(setup_deploy_fixture bad-checksum active)
  bad_sha=$(printf '%064d' 0)
  mkdir -p "$fixture/root/incoming/$bad_sha"
  printf wrong > "$fixture/root/incoming/$bad_sha/box-standalone.jar.part"
  if run_deploy "$fixture" ready "$bad_sha" >/dev/null 2>&1; then
    fail "bad checksum deployment unexpectedly succeeded"
  fi
  assert_equal "$(cat "$fixture/state/service")" active
  assert_equal "$(readlink -f -- "$fixture/root/current")" \
    "$fixture/root/releases/$(cat "$fixture/state/old-sha")"
  [[ ! -s "$fixture/state/calls" ]]
}

missing_current_is_rejected() {
  local fixture
  local sha
  fixture=$(setup_deploy_fixture missing-current active)
  sha=$(stage_release "$fixture" new-without-current)
  rm -- "$fixture/root/current"
  if run_deploy "$fixture" ready "$sha" >/dev/null 2>&1; then
    fail "deployment without a managed current release unexpectedly succeeded"
  fi
  assert_equal "$(cat "$fixture/state/service")" active
  [[ ! -s "$fixture/state/calls" ]]
}

readiness_failure_rolls_back() {
  local fixture
  local sha
  local old_sha
  fixture=$(setup_deploy_fixture rollback active)
  sha=$(stage_release "$fixture" rollback-new)
  if run_deploy "$fixture" training-then-fail "$sha" >/dev/null 2>&1; then
    fail "readiness failure unexpectedly succeeded"
  fi
  old_sha=$(cat "$fixture/state/old-sha")
  assert_equal "$(readlink -f -- "$fixture/root/current")" \
    "$fixture/root/releases/$old_sha"
  assert_equal "$(cat "$fixture/state/service")" active
  [[ -f "$fixture/root/releases/$sha/.complete" ]]
}

readiness_failure_preserves_inactive_state() {
  local fixture
  local sha
  local old_sha
  fixture=$(setup_deploy_fixture rollback-inactive inactive)
  sha=$(stage_release "$fixture" rollback-inactive-new)
  if run_deploy "$fixture" training-then-fail "$sha" >/dev/null 2>&1; then
    fail "inactive readiness failure unexpectedly succeeded"
  fi
  old_sha=$(cat "$fixture/state/old-sha")
  assert_equal "$(readlink -f -- "$fixture/root/current")" \
    "$fixture/root/releases/$old_sha"
  assert_equal "$(cat "$fixture/state/service")" inactive
}

launcher_scenarios
successful_active_deploy
successful_inactive_deploy
checksum_failure_precedes_downtime
missing_current_is_rejected
readiness_failure_rolls_back
readiness_failure_preserves_inactive_state
printf 'deployment integration scenarios passed\n'
