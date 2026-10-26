// What the game sells, with the same IDs and prices as Product in engine/.../Shop.kt (a test checks).
//   kind: vip    — VIP for [days] more days from today or from when the current VIP ends
//         once   — kept for good
//         pass   — the season pass, bought anew every month

export const CATALOG = {
    vip_month: { rubles: 50, kind: 'vip', days: 30, title: 'VIP на 30 дней' },
    vip_forever: { rubles: 399, kind: 'once', title: 'VIP навсегда' },
    starter_pack: { rubles: 99, kind: 'once', title: 'Набор новобранца' },
    commander_engineer: { rubles: 149, kind: 'once', title: 'Командир: инженер Ковалёва' },
    commander_grom: { rubles: 149, kind: 'once', title: 'Командир: капитан Гром' },
    commander_sokolova: { rubles: 149, kind: 'once', title: 'Командир: лейтенант Соколова' },
    skin_night: { rubles: 99, kind: 'once', title: 'Камуфляж «Ночь»' },
    skin_parade: { rubles: 149, kind: 'once', title: 'Камуфляж «Парад»' },
    season_pass: { rubles: 149, kind: 'pass', title: 'Пропуск сезона' },
};
