using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;

// Конфиги: повторная загрузка не должна менять размер списков и словарей.
// Newtonsoft с ObjectCreationHandling.Auto (настройки Oxide по умолчанию) дописывает элементы
// из файла к тем, что уже лежат в инициализаторе поля: 2 новости -> 4 -> 8 при каждой загрузке.
// Тест: три подряд «загрузки плагина» (новый экземпляр + LoadConfig, файл общий) - размеры
// всех коллекций конфига совпадают между загрузками и с дефолтами.
public static class ConfigCheck
{
    const BindingFlags Any = BindingFlags.Instance | BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic;
    static int fails;

    static void Ok(string name, bool cond, string detail)
    {
        Console.WriteLine((cond ? "  PASS  " : "  FAIL  ") + name + "   " + detail);
        if (!cond) fails++;
    }

    // Все коллекции внутри объекта конфига (и вложенных объектов конфига), путь -> Count.
    static void Collect(object o, string path, Dictionary<string, int> res, int depth)
    {
        if (o == null || depth > 3) return;
        foreach (var f in o.GetType().GetFields(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic))
        {
            object v = f.GetValue(o);
            if (v == null || v is string) continue;
            var col = v as ICollection;
            if (col != null) { res[path + f.Name] = col.Count; continue; }
            if (f.FieldType.IsClass && f.FieldType.Namespace != null && f.FieldType.Namespace.StartsWith("Oxide.Plugins"))
                Collect(v, path + f.Name + ".", res, depth + 1);
        }
    }

    static Dictionary<string, int> Load(Type plugin, out object cfg)
    {
        object inst = Activator.CreateInstance(plugin, true);
        plugin.GetMethod("LoadConfig", Any).Invoke(inst, null);
        var cfgField = plugin.GetFields(Any).First(f => f.Name == "_config" || f.Name == "config");
        cfg = cfgField.GetValue(inst);
        var res = new Dictionary<string, int>();
        Collect(cfg, "", res, 0);
        return res;
    }

    public static int Run()
    {
        var store = Oxide.Core.Configuration.DynamicConfigFile.Store;
        foreach (string name in new[] { "ChatSystem", "Stacks", "AccountSystem" })
        {
            Type plugin = Type.GetType("Oxide.Plugins." + name);
            store.Clear();
            object cfg;
            var loads = new List<Dictionary<string, int>>();
            for (int i = 0; i < 3; i++) loads.Add(Load(plugin, out cfg));
            // дефолты: свежий объект конфига без чтения файла
            var first = Load(plugin, out cfg);
            var defaults = new Dictionary<string, int>();
            Collect(Activator.CreateInstance(cfg.GetType(), true), "", defaults, 0);

            bool anyCollections = loads[0].Count > 0;
            Ok(name + ": в конфиге есть коллекции для проверки", anyCollections, string.Join(", ", loads[0].Keys));
            foreach (var kv in loads[0])
            {
                int d = defaults.ContainsKey(kv.Key) ? defaults[kv.Key] : -1;
                string sizes = string.Join("/", loads.Select(l => l[kv.Key]));
                Ok(name + "." + kv.Key + ": размер не растёт при перезагрузках", loads.All(l => l[kv.Key] == kv.Value) && kv.Value == d,
                   "дефолт " + d + ", загрузки " + sizes);
            }
            store.Clear();
        }
        // Уже раздутые файлы: список в JSON продублирован 4 раза - после загрузки остаются только уникальные записи.
        foreach (var t in new[] { Tuple.Create("ChatSystem", "Новости", "News"), Tuple.Create("AccountSystem", "Правила EXP за контейнеры (первое совпадение сверху вниз)", "LootRules") })
        {
            Type plugin = Type.GetType("Oxide.Plugins." + t.Item1);
            store.Clear();
            object cfg;
            var before = Load(plugin, out cfg);
            var json = Newtonsoft.Json.Linq.JObject.Parse(store[t.Item1]);
            var arr = (Newtonsoft.Json.Linq.JArray)json[t.Item2];
            int unique = arr.Count;
            var bloated = new Newtonsoft.Json.Linq.JArray();
            for (int k = 0; k < 4; k++) foreach (var e in arr) bloated.Add(e.DeepClone());
            json[t.Item2] = bloated;
            store[t.Item1] = json.ToString();
            var after = Load(plugin, out cfg);
            var reread = Newtonsoft.Json.Linq.JObject.Parse(store[t.Item1]);
            Ok(t.Item1 + "." + t.Item3 + ": раздутый файл (x4) чистится до уникальных записей и перезаписывается", after[t.Item3] == unique && ((Newtonsoft.Json.Linq.JArray)reread[t.Item2]).Count == unique,
               "в файле было " + bloated.Count + ", после загрузки " + after[t.Item3] + ", в файле теперь " + ((Newtonsoft.Json.Linq.JArray)reread[t.Item2]).Count);
            store.Clear();
        }

        Console.WriteLine(fails == 0 ? "\nCONFIG: ALL PASS" : "\nCONFIG: " + fails + " FAILED");
        return fails;
    }
}
