using System;
using System.Collections.Generic;
using UnityEngine;

// Заглушки игрового API. Сигнатуры сверены с "исходники игры/Assembly-CSharp.cs".

public struct EncryptedValue<T>
{
    private T _v;
    public static implicit operator EncryptedValue<T>(T value) { return new EncryptedValue<T> { _v = value }; }
    public static implicit operator T(EncryptedValue<T> value) { return value._v; }
    public override string ToString() { return _v == null ? "" : _v.ToString(); }
}

namespace Network
{
    public class Connection { public int authLevel; public ulong userid; public string username; }
    public struct NetworkableId { public ulong Value; }
    public class Networkable { public NetworkableId ID; public Connection connection; }
}

public class DamageTypeList
{
    public float[] types = new float[28];
    public float Total() { return 0f; }
    public bool Has(DamageType t) { return false; }
    public float Get(DamageType t) { return 0f; }
}

// Assembly-CSharp.cs: enum DamageType
public enum DamageType
{
    Generic, Hunger, Thirst, Cold, Drowned, Heat, Bleeding, Poison, Suicide, Bullet,
    Slash, Blunt, Fun_Water, Explosion, Radiation, Bite, Stab, Voice, Decay, ElectricShock,
    Arrow, AntiVehicle, Collision, Fall, Cold_Exposure, Fire, ColdExposure, AdvancedFire
}

// Assembly-CSharp.cs:349119
public enum HitArea { Head = 1, Chest = 2, Stomach = 4, Arm = 8, Hand = 0x10, Leg = 0x20, Foot = 0x40 }

public class DamageProperties { }
public class Projectile { public float conditionLoss; }

// Assembly-CSharp.cs:348683
public class HitInfo
{
    public BaseEntity HitEntity;
    public uint HitBone;
    public Vector3 HitPositionWorld;
    public Vector3 PointStart;
    public Vector3 PointEnd;
    public float ProjectileDistance;          // :348729
    public Projectile ProjectilePrefab;
    public DamageProperties damageProperties;
    public DamageTypeList damageTypes = new DamageTypeList();
    public BasePlayer InitiatorPlayer;        // :348753
    public BaseEntity Initiator;
    public AttackEntity Weapon;               // :348689
    public BaseEntity WeaponPrefab;           // :348687
    public string boneName;                   // :348819
    public HitArea boneArea;
    public bool IsProjectile() { return false; }   // :348951
}

public class BaseNetworkable : Component
{
    public Network.Networkable net;
    public string ShortPrefabName;
    public string PrefabName;
    public virtual void Kill() { }
}

public class BaseEntity : BaseNetworkable
{
    public ulong OwnerID;
    public ulong skinID;
    public BasePlayer GetOwnerPlayer() { return null; }
    public BaseEntity GetParentEntity() { return null; }
}

public class BaseCombatEntity : BaseEntity
{
    public float health;
    public float MaxHealth() { return 100f; }
    public BaseCombatEntity lastAttacker;
}

public class ItemDefinition : MonoBehaviour
{
    public int itemid;
    public string shortname;
    public string displayName_english;
}

public class Item
{
    public ItemDefinition info;
    public int amount;
    public float condition;
    public float maxCondition;
    public ulong skin;
    public bool hasCondition;
    public bool isBroken;
    public void LoseCondition(float amount) { }
    public void RepairCondition(float amount) { }
    public BasePlayer GetOwnerPlayer() { return null; }
}

public static class ItemManager
{
    public static ItemDefinition FindItemDefinition(int itemID) { return null; }
    public static ItemDefinition FindItemDefinition(string shortName) { return null; }
    public static List<ItemDefinition> GetItemDefinitions() { return new List<ItemDefinition>(); }
}

public class ItemContainer { public List<Item> itemList = new List<Item>(); }
public class PlayerInventory
{
    public ItemContainer containerMain = new ItemContainer();
    public ItemContainer containerBelt = new ItemContainer();
    public ItemContainer containerWear = new ItemContainer();
}

public class HeldEntity : BaseEntity
{
    public Item GetOwnerItem() { return null; }
    public ItemDefinition GetOwnerItemDefinition() { return null; }   // :138275
}

public class AttackEntity : HeldEntity { }                             // :288366
public class BaseProjectile : AttackEntity { }                         // :88425
public class BaseMelee : AttackEntity { }                              // :62074
public class ThrownWeapon : AttackEntity { }

public class BasePlayer : BaseCombatEntity
{
    public EncryptedValue<ulong> userID = 0UL;                         // :70180
    public string displayName;
    public string UserIDString;
    public bool IsConnected;
    public bool IsNpc;
    public bool IsSleeping() { return false; }
    public bool IsWounded() { return false; }
    public bool IsDead() { return false; }
    public PlayerInventory inventory = new PlayerInventory();
    public bool IsAdmin;
    public bool IsDestroyed;
    public void ChatMessage(string message) { }
    public void SendConsoleCommand(string command, params object[] args) { }
    public HeldEntity GetHeldEntity() { return null; }
    public static List<BasePlayer> activePlayerList = new List<BasePlayer>();
    public static List<BasePlayer> sleepingPlayerList = new List<BasePlayer>();
    public static BasePlayer FindByID(ulong userId) { return null; }
    public static BasePlayer FindAwakeOrSleeping(string nameOrId) { return null; }
}

public class NPCPlayer : BasePlayer { }
public class ScientistNPC : NPCPlayer { }
public class BaseAnimalNPC : BaseCombatEntity { }
public class BaseNpc : BaseCombatEntity { }

public class BuildingGrade { public enum Enum { None, Twigs, Wood, Stone, Metal, TopTier } }
public class StabilityEntity : BaseCombatEntity { }
public class DecayEntity : BaseCombatEntity { }
public class BuildingBlock : StabilityEntity { public BuildingGrade.Enum grade; }
public class Door : DecayEntity { }
public class BuildingPrivlidge : DecayEntity { }
public class StorageContainer : DecayEntity { public ItemContainer inventory = new ItemContainer(); }
public class LootContainer : StorageContainer { }
public class HackableLockedCrate : StorageContainer { }
public class SupplyDrop : StorageContainer { }
public class CollectibleEntity : BaseEntity { }
public class ResourceDispenser : Component { }
public class ResourceEntity : BaseEntity { }
public class BradleyAPC : BaseCombatEntity { }
public class BaseHelicopter : BaseCombatEntity { }
public class PatrolHelicopter : BaseHelicopter { }
public class AutoTurret : BaseCombatEntity { }
public class Planner : HeldEntity { }
public class ItemCraftTask { }
public class ItemCrafter : Component { public BasePlayer owner; }
public class ItemModProjectile : MonoBehaviour { }
public class SupplySignal : BaseEntity { }

public static class GameObjectEx
{
    public static BaseEntity ToBaseEntity(this GameObject go) { return null; }
}

namespace ProtoBuf
{
    public class ProjectileShoot { }
}

namespace ConVar
{
    public static class Server { public static string level; }
}

public static class ConsoleSystem
{
    public class Arg
    {
        public BasePlayer Player() { return null; }
        public string[] Args;
        public bool HasArgs(int count) { return false; }
        public string GetString(int index, string def = "") { return def; }
        public int GetInt(int index, int def = 0) { return def; }
        public ulong GetUInt64(int index, ulong def = 0UL) { return def; }
    }
}
