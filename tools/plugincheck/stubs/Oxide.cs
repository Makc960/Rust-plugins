using System;
using System.Collections.Generic;

namespace Oxide.Core
{
    public static class Utility
    {
        public static string GetFileNameWithoutExtension(string value) { return value; }   // Oxide.Core.cs:3787
    }

    // Oxide.Core.cs:3917 - struct с int-полями, не class.
    public struct VersionNumber
    {
        public int Major;
        public int Minor;
        public int Patch;

        public VersionNumber(int major, int minor, int patch)
        {
            Major = major;
            Minor = minor;
            Patch = patch;
        }

        public override string ToString() { return Major + "." + Minor + "." + Patch; }

        private long Weight { get { return Major * 100000L + Minor * 1000L + Patch; } }

        public static bool operator ==(VersionNumber a, VersionNumber b) { return a.Weight == b.Weight; }
        public static bool operator !=(VersionNumber a, VersionNumber b) { return a.Weight != b.Weight; }
        public static bool operator >(VersionNumber a, VersionNumber b) { return a.Weight > b.Weight; }
        public static bool operator <(VersionNumber a, VersionNumber b) { return a.Weight < b.Weight; }
        public static bool operator >=(VersionNumber a, VersionNumber b) { return a.Weight >= b.Weight; }
        public static bool operator <=(VersionNumber a, VersionNumber b) { return a.Weight <= b.Weight; }

        public override bool Equals(object obj) { return obj is VersionNumber && this == (VersionNumber)obj; }
        public override int GetHashCode() { return Weight.GetHashCode(); }
    }

    public class DataFileSystem
    {
        // Мини-«диск» для тестов: что записали, то и прочитаем; Writes - журнал записей.
        public static readonly Dictionary<string, object> Store = new Dictionary<string, object>();
        public static readonly List<string> Writes = new List<string>();
        public T ReadObject<T>(string name) { object o; return Store.TryGetValue(name, out o) && o is T ? (T)o : default(T); }
        public void WriteObject<T>(string name, T obj, bool sync = false) { Store[name] = obj; Writes.Add(name); }
        public bool ExistsDatafile(string name) { return Store.ContainsKey(name); }
    }

    public class OxideMod
    {
        public DataFileSystem DataFileSystem = new DataFileSystem();
        public string DataDirectory { get; private set; }
        public Oxide.Core.Plugins.PluginManager RootPluginManager = new Oxide.Core.Plugins.PluginManager();
        public void LogInfo(string format, params object[] args) { }
        public void LogWarning(string format, params object[] args) { }
        public void LogError(string format, params object[] args) { }
        public void LogException(string message, Exception ex) { }
        public bool UnloadPlugin(string name) { return true; }   // Oxide.Core.cs:2956
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
        public enum RequestMethod { DELETE, GET, PATCH, POST, PUT }   // Oxide.Core.cs:8539

        public class WebRequests
        {
            // Oxide.Core.cs:8885
            public void Enqueue(string url, string body, Action<int, string> callback, Oxide.Core.Plugins.Plugin owner,
                RequestMethod method = RequestMethod.GET, Dictionary<string, string> headers = null, float timeout = 0f) { }
        }

        public class Timer
        {
            // Отложенные Once-коллбэки; тесты вызывают их сами (Fire).
            public static readonly List<Action> Scheduled = new List<Action>();
            public static void Fire() { var l = Scheduled.ToArray(); Scheduled.Clear(); foreach (var a in l) a(); }
            public class TimerInstance { public void Destroy() { } }
            public TimerInstance Once(float delay, Action callback) { return new TimerInstance(); }
            public TimerInstance Every(float interval, Action callback) { return new TimerInstance(); }
            public TimerInstance Repeat(float interval, int repetitions, Action callback) { return new TimerInstance(); }
        }

        public class Permission
        {
            // Тесты выдают права сюда; ключ - имя права (один игрок на сценарий).
            public static readonly HashSet<string> Granted = new HashSet<string>();
            public void RegisterPermission(string name, Oxide.Core.Plugins.Plugin owner) { }
            public bool UserHasPermission(string id, string perm) { return Granted.Contains(perm); }
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
        public class PluginManager
    {
        // Реестр, чтобы сквозной тест мог связать ServerMenu и AccountSystem
        // так же, как это делает Oxide на сервере.
            public readonly Dictionary<string, Oxide.Core.Plugins.Plugin> Plugins =
                new Dictionary<string, Oxide.Core.Plugins.Plugin>();

            public Oxide.Core.Plugins.Plugin GetPlugin(string name)
        {
            Oxide.Core.Plugins.Plugin plugin;
            return name != null && Plugins.TryGetValue(name, out plugin) ? plugin : null;
        }
    }


        public class Plugin
        {
            public string Name { get; set; }
            public string Title { get; set; }
            public bool IsLoaded { get; set; }
            public VersionNumber Version { get; set; }
            public static implicit operator bool(Plugin plugin) { return plugin != null; }   // Oxide.Core.cs:5504

            // Настоящая диспетчеризация: ищем метод по имени или по
            // [HookMethod("...")], как это делает Oxide, и зовём его.
            // Без этого межплагинные вызовы в тестах были бы немыми.
            public object Call(string hook, params object[] args)
            {
                if (string.IsNullOrEmpty(hook)) return null;

                int count = args == null ? 0 : args.Length;
                const System.Reflection.BindingFlags any =
                    System.Reflection.BindingFlags.Instance |
                    System.Reflection.BindingFlags.Public |
                    System.Reflection.BindingFlags.NonPublic;

                foreach (var method in GetType().GetMethods(any))
                {
                    var parameters = method.GetParameters();
                    if (parameters.Length != count) continue;

                    if (method.Name != hook) continue;
                    return method.Invoke(this, args);
                }

                return null;
            }

            public T Call<T>(string hook, params object[] args)
            {
                object result = Call(hook, args);
                return result is T ? (T)result : default(T);
            }

            public object CallHook(string hook, params object[] args) { return Call(hook, args); }
        }

        // Oxide.Core.Plugins, как в Oxide.Core.cs.
        public class HookMethodAttribute : Attribute
        {
            public HookMethodAttribute(string name) { }
        }

        [AttributeUsage(AttributeTargets.Field)]
        public class PluginReferenceAttribute : Attribute
        {
            public PluginReferenceAttribute() { }
            public PluginReferenceAttribute(string name) { }
        }
    }
}

namespace Oxide.Game.Rust.Libraries
{
    public class Player
    {
        public void Message(BasePlayer player, string message, string prefix, ulong userId = 0UL, params object[] args) { }   // Oxide.Rust.cs:3380
        public void Message(BasePlayer player, string message, ulong userId = 0UL) { }
        public void Reply(BasePlayer player, string message, string prefix, ulong userId = 0UL, params object[] args) { }
        public void Reply(BasePlayer player, string message, ulong userId = 0UL) { }
    }

    public class Command
    {
        // Oxide.Rust.cs:2885
        public void AddChatCommand(string command, Oxide.Core.Plugins.Plugin plugin, Action<BasePlayer, string, string[]> callback) { }
        public void AddConsoleCommand(string command, Oxide.Core.Plugins.Plugin plugin, Func<ConsoleSystem.Arg, bool> callback) { }
    }
}

namespace Oxide.Game.Rust.Cui
{
    using UnityEngine;

    public interface ICuiComponent { }   // Oxide.Rust.cs:2028

    public class CuiRectTransformComponent : ICuiComponent
    {
        public string AnchorMin = "0 0";
        public string AnchorMax = "1 1";
        public string OffsetMin = "0 0";
        public string OffsetMax = "0 0";
    }

    public class CuiImageComponent : ICuiComponent
    {
        public string Color = "1 1 1 1";
        public string Sprite;
        public string Material;
        public string Png;
        public string FadeIn;
        public int ItemId;
        public ulong SkinId;
    }

    public class CuiRawImageComponent : ICuiComponent
    {
        public string Color = "1 1 1 1";
        public string Png;
        public string Url;
        public string Sprite;
        public string Material;
        public string SteamId;
    }

    public class CuiTextComponent : ICuiComponent
    {
        public string Text = "";
        public int FontSize = 14;
        public string Font;
        public string Color = "1 1 1 1";
        public TextAnchor Align = TextAnchor.UpperLeft;
        public float FadeIn;
    }

    public class CuiButtonComponent : ICuiComponent
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

    public class CuiScrollViewComponent : ICuiComponent
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

    public class CuiOutlineComponent : ICuiComponent { public string Color; public string Distance; }

    // Oxide.Rust.cs:2224
    public class CuiInputFieldComponent : ICuiComponent
    {
        public string Text { get; set; } = string.Empty;
        public int FontSize { get; set; }
        public string Font { get; set; }
        public TextAnchor Align { get; set; }
        public string Color { get; set; }
        public int CharsLimit { get; set; }
        public string Command { get; set; }
        public bool ReadOnly { get; set; }
        public string PlaceholderId { get; set; }
        public bool IsPassword { get; set; }
        public bool NeedsKeyboard { get; set; }
    }
    public class CuiNeedsCursorComponent : ICuiComponent { }
    public class CuiNeedsKeyboardComponent : ICuiComponent { }   // Oxide.Rust.cs:2560

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
        public List<ICuiComponent> Components { get; } = new List<ICuiComponent>();   // Oxide.Rust.cs:2016
        public float FadeOut;
        public string DestroyUi;
    }

    // Заглушка повторяет поведение настоящего контейнера: элементы реально
    // складываются в список, а AddUi сериализует их в JSON. Без этого замер
    // времени сборки экрана ничего бы не значил.
    public class CuiElementContainer : List<CuiElement>
    {
        public string Add(CuiPanel panel, string parent = "Hud", string name = null, string destroyUi = null)
        {
            if (string.IsNullOrEmpty(name)) name = CuiHelper.GetGuid();
            var element = new CuiElement { Name = name, Parent = parent, DestroyUi = destroyUi };
            element.Components.Add(panel.Image);
            element.Components.Add(panel.RectTransform);
            if (panel.CursorEnabled) element.Components.Add(new CuiNeedsCursorComponent());
            base.Add(element);
            return name;
        }

        public string Add(CuiLabel label, string parent = "Hud", string name = null, string destroyUi = null)
        {
            if (string.IsNullOrEmpty(name)) name = CuiHelper.GetGuid();
            var element = new CuiElement { Name = name, Parent = parent };
            element.Components.Add(label.Text);
            element.Components.Add(label.RectTransform);
            base.Add(element);
            return name;
        }

        public string Add(CuiButton button, string parent = "Hud", string name = null, string destroyUi = null)
        {
            if (string.IsNullOrEmpty(name)) name = CuiHelper.GetGuid();
            var element = new CuiElement { Name = name, Parent = parent };
            element.Components.Add(button.Button);
            element.Components.Add(button.RectTransform);
            var label = new CuiElement { Name = name + ".Text", Parent = name };
            label.Components.Add(button.Text);
            base.Add(element);
            base.Add(label);
            return name;
        }

        public new string Add(CuiElement element)
        {
            if (string.IsNullOrEmpty(element.Name)) element.Name = CuiHelper.GetGuid();
            base.Add(element);
            return element.Name;
        }
    }

    public static class CuiHelper
    {
        public static int LastJsonLength;
        public static int LastElementCount;

        // Транскрипт того, что ушло бы клиенту: "D имя" на DestroyUi, "A json" на AddUi.
        public static readonly List<string> Transcript = new List<string>();

        public static bool AddUi(BasePlayer player, CuiElementContainer container)
        {
            string json = ToJson(container);
            LastJsonLength = json.Length;
            LastElementCount = container.Count;
            Transcript.Add("A " + json);
            return true;
        }

        public static bool AddUi(BasePlayer player, string json)
        {
            LastJsonLength = json.Length;
            Transcript.Add("A " + json);
            return true;
        }

        public static bool DestroyUi(BasePlayer player, string name)
        {
            Transcript.Add("D " + name);
            return true;
        }

        public static string ToJson(CuiElementContainer container, bool format = false)
        {
            return Newtonsoft.Json.JsonConvert.SerializeObject(container);
        }

        // Имена безымянных элементов игроку не видны; для сравнения транскриптов
        // они должны быть воспроизводимыми, поэтому вместо GUID — счётчик.
        public static int GuidCounter;
        public static string GetGuid() { return "auto" + (++GuidCounter).ToString("D6"); }
    }
}
