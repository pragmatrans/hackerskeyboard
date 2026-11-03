#!/usr/bin/env python3
"""Builds the shell/busybox word list for the command-line suggestion switch.

Output is "token<TAB>weight<TAB>group", sorted by token, one per line. The group lets the IME
lift a whole family (git, docker, apt...) above everything else once it sees the word before the
cursor, without any per-token bookkeeping at runtime.

The weights only rank tokens against each other inside this list, so they can be small numbers
unlike the wordfreq corpus counts used for Ukrainian.
"""
import re
import subprocess
import sys

TOKEN = re.compile(r"^[a-z][a-z0-9._/-]{1,23}$")

# Group name is the trigger: typing it as the previous word boosts everything in that family.
GROUPS = {
    "git": "git", "commit": "git", "push": "git", "pull": "git", "fetch": "git",
    "merge": "git", "rebase": "git", "clone": "git", "branch": "git", "checkout": "git",
    "stash": "git", "status": "git", "diff": "git", "log": "git", "show": "git",
    "remote": "git", "tag": "git", "blame": "git", "cherry-pick": "git", "reset": "git",
    "revert": "git", "switch": "git", "restore": "git", "worktree": "git", "submodule": "git",
    "docker": "docker", "docker-compose": "docker", "compose": "docker",
    "apt": "apt", "apt-get": "apt", "apt-cache": "apt", "apt-mark": "apt", "pkg": "apt",
    "dpkg": "apt", "apt-key": "apt",
    "systemctl": "systemd", "service": "systemd", "journalctl": "systemd",
    "nmap": "net", "ping": "net", "ping6": "net", "dig": "net", "nslookup": "net",
    "host": "net", "whois": "net", "tcpdump": "net", "ssh": "net", "scp": "net",
    "rsync": "net", "curl": "net", "wget": "net", "netstat": "net", "ss": "net",
    "python": "py", "python3": "py", "pip": "py", "pip3": "py", "node": "py", "npm": "py",
}

# Tokens that belong to a family but do not trigger it themselves.
GROUP_OF = {
    "git-shell": "git", "git-receive-pack": "git", "git-upload-pack": "git",
    "git-upload-archive": "git",
}

# Cross-compiler and toolchain names drown everything else when you type a single letter.
NOISE = re.compile(r"(aarch64|x86_64|i686|arm-linux|linux-gnu|ctx-|pkg-config-)")

# Commands worth typing on a phone: short, common, and painful to spell.  The order inside each
# group is by how often a person actually reaches for it.
CURATED = {
    900: """
    ls cd pwd cat mv cp rm mkdir rmdir touch chmod chown ln df du free top ps kill pkill
    grep egrep fgrep sed awk cut sort uniq head tail wc tr tee xargs find locate which whereis
    echo printf env export unset alias source history clear exit sudo su
    tar gzip gunzip zip unzip curl wget ping traceroute netstat ss ifconfig ip route iptables nc
    ssh scp sftp rsync screen tmux vim vi nano less more man
    mount umount lsblk fdisk dd sync reboot shutdown crontab systemctl service journalctl
    bash sh zsh python python3 pip pip3 node npm yarn git make gcc gdb jq xxd hexdump file
    """,
    800: """
    busybox pkg termux-setup-storage termux-info termux-change-repo termux-reload-settings
    bashrc zshrc profile mksh
    """,
    780: """
    commit push pull fetch merge rebase clone branch checkout stash status diff log show
    remote tag blame cherry-pick reset revert stash-pop switch restore worktree submodule
    """,
    760: """
    docker-compose docker ps-images ps-all compose-up compose-down compose-logs run build exec
    logs images rmi pull-compose restart stop start inspect volume network prune system
    """,
    740: """
    apt-get apt-cache apt-mark pkg-install pkg-update pkg-upgrade pkg-search dpkg
    """,
    720: """
    systemctl-status journalctl-unit systemctl-start systemctl-stop systemctl-enable
    systemctl-restart systemctl-daemon-reload
    """,
    700: """
    nmap nmap-scan ping6 dig nslookup host whois tcpdump openssl ssh-keygen ssh-copy-id
    """,
    680: """
    /etc/ /etc/hosts /etc/passwd /etc/fstab /var/log/ /var/www/ /tmp/ /dev/ /proc/ /sys/
    /sdcard/ /storage/emulated/0/ /data/data/com.termux/files/home/ /data/local/tmp/
    """,
    660: """
    --help --version --verbose --force --recursive -la -al -rf -rfv -rn -nv -r
    """,
}


def from_shell():
    """Whatever this machine's shell knows about - a decent stand-in for a typical toolbox."""
    try:
        out = subprocess.run(["bash", "-lc", "compgen -c"], capture_output=True, text=True,
                             timeout=60).stdout
    except Exception as error:                     # pragma: no cover - build-time only
        print("compgen unavailable: %s" % error, file=sys.stderr)
        return []
    return [line.strip() for line in out.splitlines()]


def main():
    weights = {}
    for weight, block in CURATED.items():
        for token in block.split():
            if TOKEN.match(token) and not NOISE.search(token):
                weights[token.lower()] = weight
    for token in from_shell():
        token = token.strip().lower()
        if TOKEN.match(token) and not NOISE.search(token):
            weights.setdefault(token, 500)
    # A token seen both in the curated list and in the shell keeps the curated weight.
    rows = sorted(weights.items())
    path = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/cmd_words.txt"
    with open(path, "w", encoding="utf-8") as out:
        for token, weight in rows:
            group = GROUPS.get(token) or GROUP_OF.get(token) or ""
            out.write("%s\t%d\t%s\n" % (token, weight, group))
    print("wrote %d tokens to %s" % (len(rows), path))


if __name__ == "__main__":
    main()
