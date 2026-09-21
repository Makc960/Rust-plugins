using System;
using System.Collections.Generic;
using UnityEngine;
using Rust;

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

// ВНИМАНИЕ: эти два типа лежат в namespace Rust, а не в глобальном.
// Assembly-CSharp.cs:617733 - namespace Rust { public class DamageTypeList }
// Плагину нужен "using Rust;", иначе сервер не соберёт его, даже если
// заглушки компилируются.
namespace Rust
{
    public class DamageTypeList
    {
        public float[] types = new float[28];
        public float Total() { return 0f; }
        public bool Has(DamageType t) { return false; }
        public float Get(DamageType t) { return 0f; }
        public DamageType GetMajorityDamageType() { return DamageType.Generic; }
    }

    // Assembly-CSharp.cs:617701 - порядок и состав как в игре.
    public enum DamageType
    {
        Generic, Hunger, Thirst, Cold, Drowned, Heat, Bleeding, Poison, Suicide, Bullet,
        Slash, Blunt, Fall, Radiation, Bite, Stab, Explosion, RadiationExposure, ColdExposure,
        Decay, ElectricShock, Arrow, AntiVehicle, Collision, Fun_Water, BeeSting, Paintball,
        Cannon, LAST
    }
}

// Assembly-CSharp.cs:349119
public enum HitArea { Head = 1, Chest = 2, Stomach = 4, Arm = 8, Hand = 0x10, Leg = 0x20, Foot = 0x40 }

public class DamageProperties { }

public class EffectData { }

public partial class Effect : EffectData               // Assembly-CSharp.cs:278867
{
    public string pooledString;                                                  // :279192
    public Effect() { }
    public Effect(string effectName, Vector3 posWorld, Vector3 normWorld,
        Network.Connection sourceConnection = null) { pooledString = effectName; }          // :279204
    public Effect(string effectName, BaseEntity ent, uint boneID, Vector3 posLocal,
        Vector3 normLocal, Network.Connection sourceConnection = null) { pooledString = effectName; }        // :279210
}

public static class EffectServerRuns { }

public partial class Effect
{
    public static class server
    {
        public static void Run(string strName, BaseEntity ent, uint boneID = 0u, Vector3 posLocal = default(Vector3),
            Vector3 normLocal = default(Vector3), Network.Connection sourceConnection = null, bool broadcast = false,
            List<Network.Connection> targets = null, int number = 0) { }
        public static void Run(string strName, Vector3 posWorld = default(Vector3), Vector3 normWorld = default(Vector3),
            Vector3 up = default(Vector3), Network.Connection sourceConnection = null, bool broadcast = false,
            List<Network.Connection> targets = null) { }
    }
}

public static class EffectNetwork                      // :279293
{
    // Тесты читают, какие эффекты ушли игроку (pooledString = имя префаба).
    public static readonly List<string> Sent = new List<string>();
    public static void Send(Effect effect) { Sent.Add(effect.pooledString); }
    public static void Send(Effect effect, Network.Connection target) { Sent.Add(effect.pooledString); }   // :279360
}
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
    public virtual void Spawn() { }                          // :290145
}

public class BaseEntity : BaseNetworkable
{
    public ulong OwnerID;
    public ulong skinID;                                    // :53175
    public BaseEntity GetParentEntity() { return null; }
    public void SendNetworkUpdate(BasePlayer.NetworkQueue queue = BasePlayer.NetworkQueue.Update) { }   // :290397
}

public class BaseCombatEntity : BaseEntity
{
    public float health;
    public float MaxHealth() { return 100f; }
    public BaseCombatEntity lastAttacker;
}

// Rust.Localization.cs:25 - глобальный namespace
public class Translate
{
    public class Phrase
    {
        public string token;
        public string english { get { return token; } }
    }
}

public enum ItemCategory
{
    Weapon, Construction, Items, Resources, Attire, Tool, Medical, Food, Ammunition,
    Traps, Misc, All, Common, Component, Search, Favourite, Electrical, Fun
}

public class ItemMod : MonoBehaviour { }

public class ItemModContainer : ItemMod                 // Assembly-CSharp.cs:371408
{
    public int capacity = 6;
    public int maxStackSize;
}

public class ItemBlueprint
{
    public int amountToCreate = 1;                         // :369813
    public ItemDefinition targetItem;                      // :369823
    public float time;                                     // :369806
    public ItemAmount[] ingredients;                       // :369810
    public List<ItemAmount> GetIngredients() { return ingredients == null ? new List<ItemAmount>() : new List<ItemAmount>(ingredients); }   // :369837
}

public class ItemDefinition : MonoBehaviour
{
    public int itemid;
    public string shortname;
    public Translate.Phrase displayName;   // Assembly-CSharp.cs:370007 - НЕ строка
    public ItemCategory category;          // :370013
    public int stackable;                  // :370032
    public bool spawnAsBlueprint;          // :370059
    public Condition condition;            // :370069
    public ItemMod[] itemMods;             // :370109

    // Assembly-CSharp.cs:369922
    public struct Condition
    {
        public bool enabled;
        public float max;
        public bool repairable;
        public bool maintainMaxCondition;
    }
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
    public ItemContainer parent;
    public ItemContainer contents;
    public int position;                                      // :366125
    public bool isServer;                                     // :366131
    public string name;                                       // :366139
    public string text;                                       // :366143
    public List<ItemOwnershipShare> ownershipShares;          // :366149
    public int MaxStackable() { return info == null ? 1 : info.stackable; }   // :367875
    public void LoseCondition(float amount) { }
    public void RepairCondition(float amount) { }
    public BasePlayer GetOwnerPlayer() { return null; }
    public void MarkDirty() { }
    public BaseEntity GetWorldEntity() { return null; }       // :367685
    public BaseEntity GetHeldEntity() { return null; }        // :367716
    public void RemoveFromWorld() { }                         // :366683
    public void RemoveFromContainer() { }                     // :366715
    public bool MoveToContainer(ItemContainer newcontainer, int iTargetPos = -1, bool allowStack = true,
        bool ignoreStackLimit = false, BasePlayer sourcePlayer = null, bool allowSwap = true)
    { if (newcontainer == null) return false; if (parent != null) parent.itemList.Remove(this); parent = newcontainer; newcontainer.itemList.Add(this); return true; }   // :367037
    public BaseEntity Drop(Vector3 vPos, Vector3 vVelocity, Quaternion rotation = default(Quaternion)) { return null; }   // :367300
    public void Remove(float fTime = 0f) { }                  // :367346
    public void UseItem(int amountToConsume = 1) { amount -= amountToConsume; if (amount <= 0) { amount = 0; Remove(); } }   // :367743
    public bool IsBlueprint() { return false; }               // :366532
    public ItemDefinition blueprintTargetDef;                 // :366266
    public Item SplitItem(int split_Amount) { return null; }  // :367446
}

public static class ItemManager
{
    public static ItemDefinition FindItemDefinition(int itemID) { return null; }
    public static ItemDefinition FindItemDefinition(string shortName) { return null; }
    public static List<ItemDefinition> GetItemDefinitions() { return new List<ItemDefinition>(); }
    public static List<ItemDefinition> itemList = new List<ItemDefinition>();   // :374543
    public static Item CreateByName(string strName, int iAmount = 1, ulong skin = 0UL) { return null; }   // :374766
}

public class ItemContainer
{
    public List<Item> itemList = new List<Item>();
    public int maxStackSize;                              // :183758
    public bool allowItemsToIncreaseToMaxStackSize;
    public BasePlayer playerOwner;                        // :368141
    public Vector3 dropPosition;
    public Vector3 dropVelocity;
    public void ServerInitialize(Item parentItem, int iMaxCapacity) { }                       // :368331
    public ProtoBuf.ItemContainer Save(bool bIncludeContainer = true, bool stripBelt = false) { return new ProtoBuf.ItemContainer(); }   // :368964
    public void Load(ProtoBuf.ItemContainer container) { }                                    // :369008
}
public class PlayerInventory
{
    public ItemContainer containerMain = new ItemContainer();
    public ItemContainer containerBelt = new ItemContainer();
    public ItemContainer containerWear = new ItemContainer();
    public void FindItemsByItemID(List<Item> list, int itemid) { }   // :368938
    public ItemCrafter crafting = new ItemCrafter();                 // :368093
    // Тест: GiveItem кладёт в containerMain; false = «инвентарь полон».
    public bool GiveFails;
    public bool GiveItem(Item item, ItemContainer container = null) { if (GiveFails) return false; item.parent = containerMain; containerMain.itemList.Add(item); return true; }   // :170085
}

public class HeldEntity : BaseEntity
{
    // GetOwnerItem() в игре protected (Assembly-CSharp.cs:138256) - плагину недоступен.
    public BasePlayer GetOwnerPlayer() { return null; }               // :137969
    public ItemDefinition GetOwnerItemDefinition() { return null; }   // :138275
}

public class AttackEntity : HeldEntity { }                             // :288366
public class BaseProjectile : AttackEntity                               // :88425
{
    public class Magazine                                                // :88428
    {
        public int contents;                                             // :88445
        public ItemDefinition ammoType;                                  // :88448
    }
    public Magazine primaryMagazine;                                     // :88559
    public void SetAmmoCount(int newCount) { }                           // :89114
    public void ForceModsChanged() { }                                   // :89523
}
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
    public Network.Connection Connection;   // :70917
    public PlayerEyes eyes;
    public bool IsReceivingSnapshot;        // :70260
    public PlayerBlueprints blueprints;     // :70165
    public ulong currentTeam;               // :69731
    public RelationshipManager.PlayerTeam Team;   // :70475
    public bool CanBuild() { return true; } // :78038
    public Item GetActiveItem() { return null; }  // :75903
    public bool IsTransferring() { return false; }        // :53597
    public BaseEntity GetCachedCraftLevelWorkbench() { return null; }   // :70706
    public readonly List<object[]> Commands = new List<object[]>();      // тест: что ушло клиенту через Command
    public void Command(string strCommand, params object[] arguments) { var a = new List<object> { strCommand }; a.AddRange(arguments); Commands.Add(a.ToArray()); }   // :82683
    public enum NetworkQueue { Update, UpdateDistance, Positional }   // :67884
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

public class BuildingGrade
{
    // Assembly-CSharp.cs:429242 - None отрицательный, Twigs начинает с нуля.
    public enum Enum { None = -1, Twigs, Wood, Stone, Metal, TopTier, Count }
    public Enum type;
}
public class StabilityEntity : BaseCombatEntity { }
public class DecayEntity : BaseCombatEntity { }
public class BuildingBlock : StabilityEntity
{
    public BuildingGrade.Enum grade;
    public float wallpaperRotation;
    public float wallpaperRotation2;                          // :102783
    public ulong wallpaperID { get; private set; }
    public ulong wallpaperID2 { get; private set; }           // :102824
    public bool HasWallpaper() { return false; }              // :103747
    public bool HasWallpaper(int side) { return false; }      // :103756
    public void SetWallpaper(ulong id, int side = 0, float rotation = 0f) { }   // :103783
    public bool CanSeeWallpaperSocket(BasePlayer player, int side = 0) { return false; }   // :103940
}
public class Door : DecayEntity { }
public class BuildingPrivlidge : DecayEntity { }
public class StorageContainer : DecayEntity { public ItemContainer inventory = new ItemContainer(); }
public class LootContainer : StorageContainer { }
public class HackableLockedCrate : StorageContainer { }
public class SupplyDrop : StorageContainer { }
public class ItemAmount
{
    public ItemDefinition itemDef;
    public float amount;        // Assembly-CSharp.cs:374409
    public int itemid;
}

public class CollectibleEntity : BaseEntity        // :115839
{
    public ItemAmount[] itemList;                  // :115845
}

public class GrowableEntity : BaseCombatEntity { } // :134439
public class BaseResourceExtractor : BaseEntity { }
public class MiningQuarry : BaseResourceExtractor { }   // :301244
public class ExcavatorArm : BaseEntity { }              // :130591
public class ResourceDispenser : Component { }
public class ResourceEntity : BaseEntity { }
public class BradleyAPC : BaseCombatEntity { }
public class BaseHelicopter : BaseCombatEntity { }
public class PatrolHelicopter : BaseHelicopter { }
public class AutoTurret : BaseCombatEntity { }
public class Planner : HeldEntity { }
public class ItemCraftTask                              // :365509
{
    public ItemBlueprint blueprint;
    public float endTime;
    public int taskUID;
    public bool cancelled;
    public ProtoBuf.Item.InstanceData instanceData;
    public int amount = 1;
    public int skinID;
    public List<Item> takenItems;
    public int numCrafted;
    public float conditionScale = 1f;
    public BaseEntity workbenchEntity;
    public int attachmentID;
}

public class ItemCrafter : Component                    // :365535
{
    public List<ItemContainer> containers = new List<ItemContainer>();
    public LinkedList<ItemCraftTask> queue = new LinkedList<ItemCraftTask>();
    public int taskUID;
    public BasePlayer owner;
    public int Finished;                                // тест: сколько раз вызван FinishCrafting
    // Повторяет ванильный FinishCrafting (:365700) в части, наблюдаемой тестом: amount--, numCrafted++,
    // один предмет на вызов, списание ингредиентов из takenItems, note.craft_done, выдача или дроп.
    public void FinishCrafting(ItemCraftTask task)
    {
        Finished++;
        task.amount--;
        task.numCrafted++;
        Item item = new Item { info = task.blueprint.targetItem, amount = task.blueprint.amountToCreate, skin = (ulong)task.skinID };
        foreach (ItemAmount ingredient in task.blueprint.GetIngredients())
        {
            int need = (int)ingredient.amount;
            if (task.takenItems == null) continue;
            foreach (Item taken in task.takenItems)
            {
                if (taken.info == ingredient.itemDef) { int used = Math.Min(taken.amount, need); taken.UseItem(need); need -= used; }
                if (need <= 0) break;
            }
        }
        task.takenItems?.RemoveAll(i => i.amount == 0);
        owner.Command("note.craft_done", task.taskUID, 1, task.amount);
        Oxide.Core.Interface.CallHook("OnItemCraftFinished", task, item, this);
        if (owner.inventory.GiveItem(item)) { owner.Command("note.inv", item.info.itemid, item.amount); return; }
        owner.Command("note.inv", item.info.itemid, item.amount);
        owner.Command("note.inv", item.info.itemid, -item.amount);
        item.Drop(containers[0].dropPosition, containers[0].dropVelocity);
    }
}
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
        public BasePlayer Caller;
        public string[] Args;
        public Arg() { }
        public Arg(BasePlayer caller, params string[] args) { Caller = caller; Args = args; }
        public BasePlayer Player() { return Caller; }
        public bool HasArgs(int count = 1) { return Args != null && Args.Length >= count; }
        public string GetString(int index, string def = "") { return Args != null && index < Args.Length ? Args[index] : def; }
        public int GetInt(int index, int def = 0) { int v; return Args != null && index < Args.Length && int.TryParse(Args[index], out v) ? v : def; }
        public ulong GetUInt64(int index, ulong def = 0UL) { ulong v; return Args != null && index < Args.Length && ulong.TryParse(Args[index], out v) ? v : def; }
        public ulong GetULong(int index, ulong def = 0UL) { return GetUInt64(index, def); }   // Facepunch.Console.cs:316
        public bool GetBool(int index, bool def = false) { bool v; return Args != null && index < Args.Length && bool.TryParse(Args[index], out v) ? v : def; }   // :385
    }
}
