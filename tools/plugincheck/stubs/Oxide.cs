using System;
using System.Collections.Generic;

namespace Oxide.Core
{
    public class VersionNumber
    {
        public ushort Major, Minor, Patch;
        public override string ToString() { return Major + "." + Minor + "." + Patch; }
    }

    public class DataFileSystem
    {
        public T ReadObject<T>(string name) { return default(T); }
        public void WriteObject<T>(string name, T obj, bool sync = false) { }
        public bool ExistsDatafile(string name) { return false; }
    }

    public class PluginManager
    {
        public Oxide.Core.Plugins.Plugin GetPlugin(string name) { return null; }
    }

    public class OxideMod
    {
        public DataFileSystem DataFileSystem = new DataFileSystem();
        public string DataDirectory { get; private set; }
        public PluginManager RootPluginManager = new PluginManager();
        public void LogInfo(string format, params object[] args) { }
        public void LogWarning(string format, params object[] args) { }
        public void LogError(string format, params object[] args) { }
        public void LogException(string message, Exception ex) { }
    }

    public static class Interface
    {
        public static OxideMod Oxide = new OxideMod();
        public static object CallHook(string hook, params object[] args) { return null; }
        public static object Call(string hook, params object[] args) { return null; }
    }

    namespace Configuration
    {
        public class DynamicConfigFile
        {
            public T ReadObject<T>() { return default(T); }
            public void WriteObject<T>(T config, bool sync = false) { }
            public object this[string key] { get { return null; } set { } }
        }
    }

    namespace Libraries
    {
        public class Timer
        {
            public class TimerInstance { public void Destroy() { } }
            public TimerInstance Once(float delay, Action callback) { return new TimerInstance(); }
            public TimerInstance Every(float interval, Action callback) { return new TimerInstance(); }
            public TimerInstance Repeat(float interval, int repetitions, Action callback) { return new TimerInstance(); }
        }

        public class Permission
        {
            public void RegisterPermission(string name, Oxide.Core.Plugins.Plugin owner) { }
            public bool UserHasPermission(string id, string perm) { return false; }
            public bool PermissionExists(string name, Oxide.Core.Plugins.Plugin owner = null) { return false; }
        }

        public class Lang
        {
            public void RegisterMessages(Dictionary<string, string> messages, Oxide.Core.Plugins.Plugin plugin, string lang = "en") { }
            public string GetMessage(string key, Oxide.Core.Plugins.Plugin plugin, string userId = null) { return key; }
        }
    }

    namespace Plugins
    {
        public class Plugin
        {
            public string Name { get; set; }
            public string Title { get; set; }
            public bool IsLoaded { get; set; }
            public VersionNumber Version { get; set; }
            public object Call(string hook, params object[] args) { return null; }
            public T Call<T>(string hook, params object[] args) { return default(T); }
            public object CallHook(string hook, params object[] args) { return null; }
        }

        [AttributeUsage(AttributeTargets.Field)]
        public class PluginReferenceAttribute : Attribute
        {
            public PluginReferenceAttribute() { }
            public PluginReferenceAttribute(string name) { }
        }
    }
}

namespace Oxide.Game.Rust.Cui
{
    using UnityEngine;

    public class CuiRectTransformComponent
    {
        public string AnchorMin = "0 0";
        public string AnchorMax = "1 1";
        public string OffsetMin = "0 0";
        public string OffsetMax = "0 0";
    }

    public class CuiImageComponent
    {
        public string Color = "1 1 1 1";
        public string Sprite;
        public string Material;
        public string Png;
        public string FadeIn;
        public int ItemId;
        public ulong SkinId;
    }

    public class CuiRawImageComponent
    {
        public string Color = "1 1 1 1";
        public string Png;
        public string Url;
        public string Sprite;
        public string Material;
        public string SteamId;
    }

    public class CuiTextComponent
    {
        public string Text = "";
        public int FontSize = 14;
        public string Font;
        public string Color = "1 1 1 1";
        public TextAnchor Align = TextAnchor.UpperLeft;
        public string FadeIn;
    }

    public class CuiButtonComponent
    {
        public string Command = "";
        public string Close = "";
        public string Color = "1 1 1 1";
        public string Sprite;
        public string Material;
        public string FadeIn;
    }

    public class CuiRectTransform
    {
        public string AnchorMin = "0 0";
        public string AnchorMax = "1 1";
        public string OffsetMin = "0 0";
        public string OffsetMax = "0 0";
    }

    public class CuiScrollViewComponent
    {
        public bool Horizontal;
        public bool Vertical;
        public UnityEngine.UI.ScrollRect.MovementType MovementType;
        public bool Inertia;
        public float Elasticity;
        public float DecelerationRate;
        public float ScrollSensitivity;
        public CuiRectTransform ContentTransform = new CuiRectTransform();
        public object HorizontalScrollbar;
        public object VerticalScrollbar;
    }

    public class CuiOutlineComponent { public string Color; public string Distance; }
    public class CuiNeedsCursorComponent { }

    public class CuiPanel
    {
        public CuiImageComponent Image = new CuiImageComponent();
        public CuiRawImageComponent RawImage;
        public CuiRectTransformComponent RectTransform = new CuiRectTransformComponent();
        public bool CursorEnabled;
        public bool KeyboardEnabled;
        public float FadeOut;
    }

    public class CuiLabel
    {
        public CuiTextComponent Text = new CuiTextComponent();
        public CuiRectTransformComponent RectTransform = new CuiRectTransformComponent();
        public float FadeOut;
    }

    public class CuiButton
    {
        public CuiButtonComponent Button = new CuiButtonComponent();
        public CuiRectTransformComponent RectTransform = new CuiRectTransformComponent();
        public CuiTextComponent Text = new CuiTextComponent();
        public float FadeOut;
    }

    public class CuiElement
    {
        public string Name;
        public string Parent;
        public List<object> Components = new List<object>();
        public float FadeOut;
        public bool DestroyUi;
    }

    public class CuiElementContainer : List<CuiElement>
    {
        public string Add(CuiPanel panel, string parent = "Hud", string name = null, string destroyUi = null) { return name; }
        public string Add(CuiLabel label, string parent = "Hud", string name = null, string destroyUi = null) { return name; }
        public string Add(CuiButton button, string parent = "Hud", string name = null, string destroyUi = null) { return name; }
        public string Add(CuiElement element) { return element.Name; }
    }

    public static class CuiHelper
    {
        public static bool AddUi(BasePlayer player, CuiElementContainer container) { return true; }
        public static bool AddUi(BasePlayer player, string json) { return true; }
        public static bool DestroyUi(BasePlayer player, string name) { return true; }
        public static string ToJson(CuiElementContainer container, bool format = false) { return ""; }
        public static string GetGuid() { return Guid.NewGuid().ToString("N"); }
    }
}
