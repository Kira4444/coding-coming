# Notes / caveats

## Password handling (important)

- **SSH config cannot store passwords.** OpenSSH has no directive for that. Anything that "auto-fills" a password (`sshpass`, expect scripts, `SSH_ASKPASS` wrappers) is a credential-leak waiting to happen and is deliberately not included.
- The config uses `IdentityFile ~/.ssh/id_ed25519` + `IdentitiesOnly yes`. This means:
  - If the key is registered in JumpServer → passwordless login.
  - If the key is NOT registered → login fails (or falls back to password prompt only if you remove those two lines).
- **Never commit this file to git.** `~/.ssh/config` is local config, not repo content. The file itself contains no secrets, but the habit of checking in SSH configs is bad.
- JumpServer may enforce MFA / password + key together. In that case you will still be prompted for a password or TOTP even with a key. That is a JumpServer policy, not a config problem.

## What the user still needs to do

1. **Install the config** to `~/.ssh/config` (or `C:\Users\<you>\.ssh\config` on Windows). Nothing is live until then.
2. **Register an SSH public key** in JumpServer (个人信息 → SSH 公钥), or accept password prompts each time.
3. **Adjust `IdentityFile`** if their key is not `~/.ssh/id_ed25519` (e.g. `id_rsa`, or a path under Windows `%USERPROFILE%\.ssh\`).
4. **Confirm the JumpServer login name is literally `admin`** — the brief said 账号 admin, so it is assumed. If the real account differs, change the first segment of every `User` line.
5. **Confirm all three assets really use system user `root`.** One non-root asset means its `User` middle segment must change.
6. **Decide IP vs asset-name routing.** Config uses IP form (`admin@root@10.1.2.3`). Some JumpServer deployments disable IP routing and require the asset name (`admin@root@web-01`). Both templates are in the file's trailing Notes.
7. **First connection will prompt for host key acceptance** (`yes` to continue). Expected, not an error.

## Design decisions worth flagging

### Why not ProxyJump?

A naive answer would be:

```
Host web-01
    HostName 10.1.2.3
    User root
    ProxyJump jms
```

That is correct for a **plain SSH bastion** (sshd with TCP forwarding). It is **wrong for JumpServer's KoKo gateway on port 2222**.

JumpServer 2222 is a menu/proxy service, not an sshd that forwards TCP to internal hosts. `ProxyJump` will fail or land you in the asset picker instead of on the target box. The supported non-interactive route is encoding the target into the SSH username:

```
<JumpServer用户>@<系统用户>@<资产IP或名称>
```

so `User admin@root@10.1.2.3` is the correct primitive here. If their deployment later exposes a real bastion sshd (some installs do, on a separate port), the ProxyJump form is documented as a fallback at the bottom of `ssh_config`.

### Why `RequestTTY yes`?

JumpServer's KoKo gateway is interactive by nature (it may drop you into a shell or a short status banner). Forcing a TTY avoids `ssh web-01 uptime` style invocations dying with "not a terminal" from the gateway.

### Why no ControlMaster by default?

Connection reuse is nice but it opens a persistent Unix socket under `~/.ssh/`. On shared or audited jump hosts some teams forbid it. Left commented/optional so the user opts in knowingly.

### Why `IdentitiesOnly yes`?

Without it, OpenSSH will try every key in `ssh-agent` plus every default identity file against JumpServer. That can trip account lockout policies after a few failures. Pinning to one key is safer.

## Assumptions baked in (flag if wrong)

| Assumption | Value used | Where to change |
|---|---|---|
| JumpServer host | `jms.company.com` | `HostName` in each block |
| JumpServer SSH port | `2222` | `Port` |
| JumpServer login user | `admin` | first segment of `User` |
| System user on assets | `root` | middle segment of `User` |
| Asset addressing | by IP | last segment of `User` |
| Local key path | `~/.ssh/id_ed25519` | `IdentityFile` |

## Non-goals / not included

- No password automation.
- No `sshpass` / expect / plink wrappers.
- No per-user `known_hosts` pinning script (OpenSSH handles this on first connect).
- No VPN / DNS prerequisites — assumes `jms.company.com` resolves and 2222 is reachable from the workstation. If it is only reachable from a corp network, that is outside this config.
