using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using Newtonsoft.Json.Linq;

// Виртуальный экран: применяет транскрипт AddUi/DestroyUi так, как это делает клиент
// (при поиске родителя и при DestroyUi побеждает последний элемент с этим именем,
// DestroyUi убирает всё поддерево). Нужен там, где плагин перерисовывает не весь экран,
// а только изменившуюся часть: сравнивается то, что в итоге на экране, а не последовательность команд.
// Дети сравниваются как множество (плитки сетки не пересекаются, порядок среди них не виден);
// порядок внутри плитки покрыт побайтным сравнением полных отрисовок тем же кодом.
public class XSkinScreen
{
    class El { public string Name, Parent, Body; public El Owner; public List<El> Children = new List<El>(); }
    readonly Dictionary<string, El> dict = new Dictionary<string, El>();
    readonly List<El> roots = new List<El>();

    public void Apply(string line)
    {
        if (line.StartsWith("D ")) { Destroy(line.Substring(2)); return; }
        if (!line.StartsWith("A ")) return;
        foreach (JObject o in JArray.Parse(line.Substring(2)))
        {
            string name = (string)o["Name"], parent = (string)o["Parent"], destroy = (string)o["DestroyUi"];
            if (!string.IsNullOrEmpty(destroy)) Destroy(destroy);
            var body = new JObject(o); body.Remove("Name"); body.Remove("Parent"); body.Remove("DestroyUi");
            var el = new El { Name = name, Parent = parent, Body = body.ToString(Newtonsoft.Json.Formatting.None) };
            El p;
            if (parent != null && dict.TryGetValue(parent, out p)) { p.Children.Add(el); el.Owner = p; } else roots.Add(el);
            dict[name] = el;
        }
    }

    void Destroy(string name)
    {
        El el;
        if (!dict.TryGetValue(name, out el)) return;
        Remove(el);
        if (el.Owner != null) el.Owner.Children.Remove(el); else roots.Remove(el);
    }

    void Remove(El el)
    {
        El reg;
        if (dict.TryGetValue(el.Name, out reg) && reg == el) dict.Remove(el.Name);
        foreach (var c in el.Children) Remove(c);
    }

    // Имена, которых игрок не видит: сгенерированные (auto000123) и номера плиток (.Skin12 -> .Skin).
    public static string Norm(string name)
    {
        name = Regex.Replace(name, @"auto\d+", "*");            // auto000023, auto000023.Text
        return Regex.Replace(name, @"^\.Skin\d+$", ".Skin");
    }

    public static string NormLine(string line)
    {
        return Regex.Replace(line, "\"\\.Skin\\d+\"", "\".Skin\"");
    }

    static string Canon(El el)
    {
        var kids = el.Children.Select(Canon).OrderBy(s => s, StringComparer.Ordinal);
        return Norm(el.Name) + "{" + el.Body + "}[" + string.Join(",", kids) + "]";
    }

    public string Canonical()
    {
        return string.Join("\n", roots.Select(r => r.Parent + "/" + Canon(r)).OrderBy(s => s, StringComparer.Ordinal));
    }
}
