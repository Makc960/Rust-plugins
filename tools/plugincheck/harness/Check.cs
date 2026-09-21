using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using Newtonsoft.Json;

public static class Check
{
    static int fails;
    static Type AS, StoredT, AccT, LayerT;

    static object F(object o, string name)
    {
        var t = o.GetType();
        var f = t.GetField(name, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
        if (f != null) return f.GetValue(o);
        var p = t.GetProperty(name, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
        if (p == null) throw new Exception("no member " + name + " on " + t.Name);
        return p.GetValue(o);
    }

    static void Ok(string name, bool cond, string detail)
    {
        Console.WriteLine((cond ? "  PASS  " : "  FAIL  ") + name + "   " + detail);
        if (!cond) fails++;
    }

    static Type Nested(string name)
    {
        return AS.GetNestedType(name, BindingFlags.NonPublic | BindingFlags.Public);
    }

    public static int Main()
    {
        string golden = Environment.GetEnvironmentVariable("GOLDEN");
        if (!string.IsNullOrEmpty(golden))
            return XSkinGolden.Run(golden, Environment.GetEnvironmentVariable("GOLDEN_FILE") ?? "xskin.golden");

        AS = Type.GetType("Oxide.Plugins.AccountSystem");
        StoredT = Nested("StoredData");
        AccT = Nested("AccountData");
        LayerT = Nested("StatLayer");
        Ok("типы найдены", AS != null && StoredT != null && AccT != null && LayerT != null,
           "AccountSystem/StoredData/AccountData/StatLayer");

        // --- миграция файла версии 1 ---
        string legacy = @"{
          ""Version"": 1,
          ""CurrentWipeId"": ""wipe-old"",
          ""Accounts"": {
            ""76561198000000001"": {
              ""UserId"": 76561198000000001,
              ""Name"": ""Tester"",
              ""Level"": 7,
              ""Exp"": 120,
              ""TotalExp"": 54321,
              ""FirstSeenUtc"": 1700000000,
              ""LastSeenUtc"": 1700009999,
              ""PlaySeconds"": 36000,
              ""Sessions"": 42,
              ""WipesPlayed"": 3,
              ""PlayerKills"": 150,
              ""Deaths"": 75,
              ""Headshots"": 40,
              ""HitsLanded"": 900,
              ""ShotsFired"": 3000,
              ""DamageDealt"": 12345.5,
              ""DamageReceived"": 9876.25,
              ""GatheredTotal"": 500000,
              ""NpcKills"": 60,
              ""AnimalKills"": 25,
              ""RocketsFired"": 11,
              ""Resources"": { ""wood"": 300000, ""stones"": 200000 },
              ""NpcKillsByPrefab"": { ""scientistnpc_heavy"": 12 },
              ""ExplosivesByPrefab"": { ""explosive.timed.deployed"": 9 }
            }
          }
        }";

        object stored = JsonConvert.DeserializeObject(legacy, StoredT);
        var accounts = (IDictionary)F(stored, "Accounts");
        object acc = accounts[76561198000000001UL];

        object life = F(acc, "Lifetime");
        object cur = F(acc, "Current");
        object prev = F(acc, "Previous");

        Ok("старые убийства ушли во «всё время»", (long)F(life, "PlayerKills") == 150,
           "Lifetime.PlayerKills=" + F(life, "PlayerKills"));
        Ok("старое время ушло во «всё время»", (long)F(life, "PlaySeconds") == 36000,
           "Lifetime.PlaySeconds=" + F(life, "PlaySeconds"));
        Ok("старый урон ушёл во «всё время»", Math.Abs((double)F(life, "DamageDealt") - 12345.5) < 0.001,
           "Lifetime.DamageDealt=" + F(life, "DamageDealt"));
        Ok("старые словари ушли во «всё время»",
           ((IDictionary)F(life, "Resources")).Count == 2 &&
           (long)((IDictionary)F(life, "Resources"))["wood"] == 300000,
           "Resources=" + ((IDictionary)F(life, "Resources")).Count);
        Ok("NpcKillsByPrefab перенесён", ((IDictionary)F(life, "NpcKillsByPrefab")).Count == 1, "1 запись");
        Ok("текущий вайп начался с нуля",
           (long)F(cur, "PlayerKills") == 0 && (long)F(cur, "PlaySeconds") == 0 &&
           ((IDictionary)F(cur, "Resources")).Count == 0, "Current пуст");
        Ok("прошлый вайп пуст", (long)F(prev, "PlayerKills") == 0, "Previous пуст");
        Ok("уровень и EXP не тронуты",
           (int)F(acc, "Level") == 7 && (long)F(acc, "TotalExp") == 54321,
           "Level=" + F(acc, "Level") + " TotalExp=" + F(acc, "TotalExp"));

        // --- обратная запись не содержит старых плоских ключей ---
        string round = JsonConvert.SerializeObject(stored);

        // Проверяем именно верхний уровень аккаунта: внутри Lifetime те же имена законны.
        var topLevel = new HashSet<string>(
            Newtonsoft.Json.Linq.JObject.Parse(JsonConvert.SerializeObject(acc))
                .Properties().Select(x => x.Name));
        string[] legacyNames = { "PlayerKills", "Deaths", "PlaySeconds", "Sessions", "Resources",
                                 "ShotsFired", "DamageDealt", "NpcKillsByPrefab", "RocketsFired" };
        var leaked = legacyNames.Where(topLevel.Contains).ToArray();
        Ok("старые плоские ключи не пишутся обратно", leaked.Length == 0,
           leaked.Length == 0 ? "верхний уровень чист" : "утекло: " + string.Join(",", leaked));
        Ok("write-only свойства не сериализуются",
           !topLevel.Any(x => x.StartsWith("Legacy")), "Legacy* отсутствуют");
        Ok("в файле есть три слоя",
           round.Contains("\"Current\"") && round.Contains("\"Previous\"") && round.Contains("\"Lifetime\""),
           "Current/Previous/Lifetime");

        // --- повторное чтение нового формата ничего не теряет ---
        object again = JsonConvert.DeserializeObject(round, StoredT);
        object acc2 = ((IDictionary)F(again, "Accounts"))[76561198000000001UL];
        Ok("новый формат читается без потерь",
           (long)F(F(acc2, "Lifetime"), "PlayerKills") == 150 &&
           (long)F(F(acc2, "Lifetime"), "PlaySeconds") == 36000,
           "Lifetime сохранён");

        Console.WriteLine();
        fails += Behaviour.Run();
        Console.WriteLine();
        fails += Ui.Run();
        Console.WriteLine();
        fails += Integration.Run();
        Console.WriteLine();
        fails += RatesCheck.Run();
        Console.WriteLine();
        fails += ChatCheck.Run();
        Console.WriteLine();
        fails += StacksCheck.Run();
        Console.WriteLine();
        fails += XSkinHooks.Run();
        Console.WriteLine();
        fails += ConfigCheck.Run();

        Console.WriteLine(fails == 0 ? "\nALL PASS" : "\n" + fails + " FAILED");
        return fails;
    }
}
