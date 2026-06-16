# MinecraftCombatBot - Beta

A high-performance client-side utility mod built natively for **Minecraft 1.21.11** using the **Fabric** framework. This mod features smart close-quarters spacing adjustments, predictive target vector tracking, and dynamic environment calculations.

> [!WARNING]  
> **Use with caution:** This mod automates player movements and actions. Running this on public multiplayer servers without authorization will likely trigger anti-cheat systems and result in a permanent ban. It is intended strictly for private testing and educational research.

---

## 🛠️ Feature Overview

<table width="100%">
  <tr>
    <td width="50%" valign="top">
      <h3>🎯 <a href="#">CombatBot Engine</a></h3>
      <p>Scans nearby blocks to find optimal coordinates for obsidian or anchor placements. Uses Line-of-Sight checks via vanilla raycasting to prevent blind target errors.</p>
    </td>
    <td width="50%" valign="top">
      <h3>⚔️ <a href="#">SwordBot Engine</a></h3>
      <p>Maintains dynamic close-quarters spacing between 2.7 and 3.1 blocks. Implements fluid client-side look angles based on target velocity, automatic W-Tapping, and randomized strafe patterns.</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>🚀 <a href="#">Fluid Elytra Flight</a></h3>
      <p>Calculates lookahead landing matrices while gliding. Handles automated firework rocket triggers, directional alignment adjustments, and drops into instant armor swaps before touching the ground.</p>
    </td>
    <td width="50%" valign="top">
      <h3>📊 <a href="#">Threaded Telemetry Logs</a></h3>
      <p>Utilizes an asynchronous single-thread executor pool to dump real-time gameplay metrics, combo interruptions, and hit-registry misfires directly to disk without hindering render frame rates.</p>
    </td>
  </tr>
</table>

---

## ⚙️ How It Decides (System Logic Flow)

```mermaid
flowchart TD
    Start[Every Client Tick] --> Replenish[Check Offhand: Automatic Totem Refill]
    Replenish --> TargetCheck{Is Target closer than 15 blocks?}
    
    TargetCheck -- No --> Standby[Reset Bot States & Release Keys]
    TargetCheck -- Yes --> EscapeCheck{Is Bot under consecutive hits?}
    
    EscapeCheck -- Yes --> Escape[Trigger Anti-Combo Evasion: Force backpedal for 2 sec]
    EscapeCheck -- No --> ModuleSplit{Which Module is running?}
    
    ModuleSplit -- SwordBot --> AimLoop[Run Predictive Angle Smoothing 1.0F - 1.3F]
    AimLoop --> DistanceLoop{Check Spacing Box}
    
    DistanceLoop -- Too Close (<2.7) --> Backpedal[Press Back Key / Release Sprint]
    DistanceLoop -- Too Far (>3.1) --> Forward[Press Forward Key / Chase Target]
    DistanceLoop -- Ideal Range --> CooldownCheck{Attack Cooldown Ready & Facing Target?}
    
    CooldownCheck -- Yes --> Swing[Attack Target -> Trigger W-Tap Tick -> Increment Combo]
    CooldownCheck -- No --> Strafe[Execute Random Multi-Axis Strafe Loop]
    
    ModuleSplit -- CombatBot --> RangeCheck{Is Target within 4.2 block reach?}
    RangeCheck -- No --> FlightCheck{Is Target > 10 blocks or Airborne?}
    FlightCheck -- Yes --> ElytraLoop[Deploy Elytra -> Trigger Firework Rockets -> Aim Path]
    FlightCheck -- No --> GroundArmor[Equip Defensive Netherite/Diamond Chestplate]
    
    RangeCheck -- Yes --> BlockingCheck{Is Target Blocking with Shield?}
    BlockingCheck -- Yes --> BreakShield[Equip Axe -> Disable Shield -> Run Anchor Setup]
    BlockingCheck -- No --> MultiWeapon{Is Player falling down?}
    MultiWeapon -- Yes --> Mace[Equip Mace -> Execute Mace Strike]
    MultiWeapon -- No --> CyclePos{Select best available option}
    
    CyclePos -- Respawn Anchor --> AnchorPhase[Place Anchor -> Charge with Glowstone -> Detonate with Sword]
    CyclePos -- End Crystal --> CrystalPhase[Place Obsidian -> Mount Crystal -> Instant Explode]
