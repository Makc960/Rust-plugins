using System;
using System.Collections;
using System.Collections.Generic;
using UnityEngine;

// Заглушки под XSkinMenu. Строки — Assembly-CSharp.cs, если не указано иное.

public class GameObjectRef { public string resourcePath; }
public class ScriptableObjectRef { }

public class ItemOwnershipShare { }

public class ItemModDeployable : MonoBehaviour               // :372154
{
    public GameObjectRef entityPrefab;                       // :370732
}

public class SteamInventoryItem : ScriptableObject           // :431933
{
    public Translate.Phrase displayName;
}

public class ItemSkin : SteamInventoryItem                   // :430281
{
    public ItemDefinition Redirect;
}

public class ItemSkinDirectory : ScriptableObject            // :430346
{
    public struct Skin
    {
        public int id;
        public int itemid;
        public string name;
        public SteamInventoryItem invItem;
    }

    public Skin[] skins;
    public static ItemSkinDirectory Instance;
    public static Skin[] ForItem(ItemDefinition item) { return new Skin[0]; }   // :430398
}

public enum BUTTON { FIRE_PRIMARY = 1, FIRE_SECONDARY = 0x800, RELOAD = 0x2000 }   // :365380

public class InputState
{
    public bool WasJustPressed(BUTTON btn) { return false; }   // :365440
}

public class PlayerEyes : Component                          // :312741
{
    public Ray HeadRay() { return new Ray(); }               // :312928
}

public class SteamInventory : Component                      // :200676 HasItem
{
    public bool HasItem(int itemid) { return false; }
}

public class PlayerBlueprints : Component
{
    public SteamInventory steamInventory;                    // :312532
}

public static class RelationshipManager
{
    public class PlayerTeam                                  // :177436
    {
        public List<ulong> members = new List<ulong>();      // :177450
    }
}

public class BaseMountable : BaseCombatEntity { }

public class GameManager                                     // :346540
{
    public static GameManager server = new GameManager();
    public BaseEntity CreateEntity(string strPrefab, Vector3 pos = default(Vector3),
        Quaternion rot = default(Quaternion), bool startActive = true) { return null; }   // :346662
}

public class BaseVehicle : BaseMountable { }                 // :93038

public class Chainsaw : BaseMelee                            // :108624
{
    public int ammo = 100;
}

namespace UnityEngine
{
    public static class CoroutineEx                          // :532808, namespace UnityEngine
    {
        public static WaitForSeconds waitForSeconds(float seconds) { return new WaitForSeconds(); }
    }
}

public class SingletonComponent<T> : MonoBehaviour where T : MonoBehaviour
{
    public static T Instance;
}

public class ServerMgr : SingletonComponent<ServerMgr>       // :425511
{
    public Coroutine StartCoroutine(IEnumerator routine) { return new Coroutine(); }
    public void StopCoroutine(Coroutine routine) { }
}

public static class RaycastHitEx
{
    public static BaseEntity GetEntity(this RaycastHit hit) { return null; }   // :449265
}

public static class InvokeHandlerEx
{
    public static void Invoke(this Component behaviour, Action action, float time) { }
}

public sealed class PooledList<T> : List<T>, IDisposable      // Facepunch.System.cs:2333
{
    public void Dispose() { }
}

namespace Facepunch
{
    public static class Pool
    {
        public static T Get<T>() where T : class, new() { return new T(); }   // Facepunch.System.cs:5207
        public static void Free<T>(ref T obj) where T : class { obj = null; }
    }
}

namespace ProtoBuf
{
    public class ItemContainer                               // Rust.Data.cs
    {
        public int slots;                                    // :143807
    }
}

public class Skinnable                                       // Rust.Workshop.cs:53, глобальный
{
    public string Name;
    public string ItemName;                                  // :55
    public static Skinnable[] All;                           // :84
}

namespace Rust.Workshop
{
    public class ApprovedSkinInfo                            // :11311
    {
        public ulong InventoryId { get; private set; }       // :11313
        public ulong WorkshopdId { get; private set; }       // :11319
        public string Name;
        public Skinnable Skinnable;                          // :10818
    }

    public static class Approved                             // :901
    {
        public static IReadOnlyDictionary<ulong, ApprovedSkinInfo> All =
            new Dictionary<ulong, ApprovedSkinInfo>();       // :905
    }
}
