package com.makc.espcamera

enum class Kind(val color: Int) {
    PERSON(0xFFFF3B3B.toInt()),
    VEHICLE(0xFFFF9F1A.toInt()),
    ANIMAL(0xFFFFE14D.toInt()),
    ITEM(0xFF2EE6FF.toInt()),
}

/** Russian name, colour group and typical real height (metres) of a COCO class. */
class LabelInfo(val title: String, val kind: Kind, val heightM: Float?)

object Labels {
    private val P = Kind.PERSON
    private val V = Kind.VEHICLE
    private val A = Kind.ANIMAL
    private val I = Kind.ITEM

    // Heights are only given where the class has a fairly constant size,
    // otherwise the distance estimate would be meaningless.
    private val byName: Map<String, LabelInfo> = mapOf(
        "person" to LabelInfo("ЧЕЛОВЕК", P, 1.70f),

        "bicycle" to LabelInfo("ВЕЛОСИПЕД", V, 1.05f),
        "car" to LabelInfo("МАШИНА", V, 1.50f),
        "motorcycle" to LabelInfo("МОТОЦИКЛ", V, 1.15f),
        "airplane" to LabelInfo("САМОЛЁТ", V, null),
        "bus" to LabelInfo("АВТОБУС", V, 3.00f),
        "train" to LabelInfo("ПОЕЗД", V, null),
        "truck" to LabelInfo("ГРУЗОВИК", V, 3.00f),
        "boat" to LabelInfo("ЛОДКА", V, null),

        "bird" to LabelInfo("ПТИЦА", A, 0.20f),
        "cat" to LabelInfo("КОШКА", A, 0.30f),
        "dog" to LabelInfo("СОБАКА", A, 0.60f),
        "horse" to LabelInfo("ЛОШАДЬ", A, 1.60f),
        "sheep" to LabelInfo("ОВЦА", A, 0.90f),
        "cow" to LabelInfo("КОРОВА", A, 1.40f),
        "elephant" to LabelInfo("СЛОН", A, 3.00f),
        "bear" to LabelInfo("МЕДВЕДЬ", A, 1.20f),
        "zebra" to LabelInfo("ЗЕБРА", A, 1.40f),
        "giraffe" to LabelInfo("ЖИРАФ", A, 4.50f),

        "traffic light" to LabelInfo("СВЕТОФОР", I, 0.90f),
        "fire hydrant" to LabelInfo("ГИДРАНТ", I, 0.70f),
        "stop sign" to LabelInfo("ЗНАК СТОП", I, 0.75f),
        "parking meter" to LabelInfo("ПАРКОМАТ", I, 1.30f),
        "bench" to LabelInfo("СКАМЕЙКА", I, 0.85f),
        "backpack" to LabelInfo("РЮКЗАК", I, 0.45f),
        "umbrella" to LabelInfo("ЗОНТ", I, null),
        "handbag" to LabelInfo("СУМКА", I, 0.30f),
        "tie" to LabelInfo("ГАЛСТУК", I, null),
        "suitcase" to LabelInfo("ЧЕМОДАН", I, 0.65f),
        "frisbee" to LabelInfo("ФРИСБИ", I, null),
        "skis" to LabelInfo("ЛЫЖИ", I, null),
        "snowboard" to LabelInfo("СНОУБОРД", I, null),
        "sports ball" to LabelInfo("МЯЧ", I, 0.22f),
        "kite" to LabelInfo("ВОЗДУШНЫЙ ЗМЕЙ", I, null),
        "baseball bat" to LabelInfo("БИТА", I, null),
        "baseball glove" to LabelInfo("ПЕРЧАТКА", I, null),
        "skateboard" to LabelInfo("СКЕЙТ", I, null),
        "surfboard" to LabelInfo("СЁРФ", I, null),
        "tennis racket" to LabelInfo("РАКЕТКА", I, null),
        "bottle" to LabelInfo("БУТЫЛКА", I, 0.25f),
        "wine glass" to LabelInfo("БОКАЛ", I, 0.20f),
        "cup" to LabelInfo("КРУЖКА", I, 0.10f),
        "fork" to LabelInfo("ВИЛКА", I, null),
        "knife" to LabelInfo("НОЖ", I, null),
        "spoon" to LabelInfo("ЛОЖКА", I, null),
        "bowl" to LabelInfo("МИСКА", I, null),
        "banana" to LabelInfo("БАНАН", I, null),
        "apple" to LabelInfo("ЯБЛОКО", I, 0.08f),
        "sandwich" to LabelInfo("СЭНДВИЧ", I, null),
        "orange" to LabelInfo("АПЕЛЬСИН", I, 0.08f),
        "broccoli" to LabelInfo("БРОККОЛИ", I, null),
        "carrot" to LabelInfo("МОРКОВЬ", I, null),
        "hot dog" to LabelInfo("ХОТ-ДОГ", I, null),
        "pizza" to LabelInfo("ПИЦЦА", I, null),
        "donut" to LabelInfo("ПОНЧИК", I, null),
        "cake" to LabelInfo("ТОРТ", I, null),
        "chair" to LabelInfo("СТУЛ", I, 0.90f),
        "couch" to LabelInfo("ДИВАН", I, 0.85f),
        "potted plant" to LabelInfo("РАСТЕНИЕ", I, null),
        "bed" to LabelInfo("КРОВАТЬ", I, null),
        "dining table" to LabelInfo("СТОЛ", I, 0.75f),
        "toilet" to LabelInfo("УНИТАЗ", I, 0.75f),
        "tv" to LabelInfo("ТЕЛЕВИЗОР", I, null),
        "laptop" to LabelInfo("НОУТБУК", I, 0.23f),
        "mouse" to LabelInfo("МЫШЬ", I, null),
        "remote" to LabelInfo("ПУЛЬТ", I, null),
        "keyboard" to LabelInfo("КЛАВИАТУРА", I, null),
        "cell phone" to LabelInfo("ТЕЛЕФОН", I, 0.15f),
        "microwave" to LabelInfo("МИКРОВОЛНОВКА", I, 0.30f),
        "oven" to LabelInfo("ДУХОВКА", I, null),
        "toaster" to LabelInfo("ТОСТЕР", I, null),
        "sink" to LabelInfo("РАКОВИНА", I, null),
        "refrigerator" to LabelInfo("ХОЛОДИЛЬНИК", I, 1.80f),
        "book" to LabelInfo("КНИГА", I, 0.22f),
        "clock" to LabelInfo("ЧАСЫ", I, null),
        "vase" to LabelInfo("ВАЗА", I, null),
        "scissors" to LabelInfo("НОЖНИЦЫ", I, null),
        "teddy bear" to LabelInfo("ПЛЮШЕВЫЙ МИШКА", I, null),
        "hair drier" to LabelInfo("ФЕН", I, null),
        "toothbrush" to LabelInfo("ЗУБНАЯ ЩЁТКА", I, null),
    )

    fun of(name: String): LabelInfo =
        byName[name] ?: LabelInfo(name.uppercase(), Kind.ITEM, null)
}
