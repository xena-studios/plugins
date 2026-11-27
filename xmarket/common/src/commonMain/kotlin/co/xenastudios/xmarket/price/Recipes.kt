package co.xenastudios.xmarket.price

/**
 * Every way one item is made from others, as xMarket's price list and `/shop audit` see it: a
 * vanilla version's crafting, furnace, stonecutter and smithing recipes (xmarket/prices/extract.ts
 * writes them from Mojang's own data), brewing, and what players do by hand (filling a bottle,
 * stripping a log, letting copper weather), which make one item into another as surely.
 *
 * A recipe's [ingredients] are each one of several items ([Ingredient.options]), so the cheapest
 * is what it costs.
 */
public class Recipe(
    /** `c` crafting, `f` a furnace of any kind, `s` the stonecutter, `m` smithing, `b` brewing, `p` by hand. */
    public val kind: Char,
    /** The key of what it makes ([ItemSpec.key]). */
    public val result: String,
    public val count: Int,
    public val ingredients: List<Ingredient>,
) {
    override fun toString(): String = "$kind $result $count ${ingredients.joinToString(" ")}"
}

/** [count] of any one of [options] (keys). */
public class Ingredient(public val options: List<String>, public val count: Int) {
    override fun toString(): String = options.joinToString("|") + if (count == 1) "" else "*$count"
}

/** A set of recipes, read from xMarket's compact text ([parse]). */
public class RecipeBook(public val recipes: List<Recipe>) {
    /** By what they make. */
    public val byResult: Map<String, List<Recipe>> = recipes.groupBy { it.result }

    /** These recipes and [other]'s. */
    public operator fun plus(other: RecipeBook): RecipeBook = RecipeBook(recipes + other.recipes)

    public companion object {
        /**
         * Reads recipes.txt's lines: `#tag a b c` defines a tag, `c oak_stairs 4 oak_planks*6`
         * a recipe whose ingredients are `x`, `x*n`, `a|b` or `#tag`. Comments are `# ` lines.
         * What it can't read is skipped.
         */
        public fun parse(text: String): RecipeBook {
            val tags = HashMap<String, List<String>>()
            val recipes = ArrayList<Recipe>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("# ")) continue
                val parts = line.split(' ').filter { it.isNotEmpty() }
                if (line.startsWith('#')) {
                    tags[parts[0].substring(1)] = parts.drop(1)
                    continue
                }
                if (parts.size < 4 || parts[0].length != 1) continue
                val count = parts[2].toIntOrNull() ?: continue
                val ingredients = ArrayList<Ingredient>()
                for (token in parts.subList(3, parts.size)) {
                    val star = token.lastIndexOf('*')
                    val name = if (star < 0) token else token.substring(0, star)
                    val n = if (star < 0) 1 else token.substring(star + 1).toIntOrNull() ?: 1
                    val options = if (name.startsWith('#')) tags[name.substring(1)].orEmpty() else name.split('|')
                    if (options.isEmpty()) continue
                    ingredients += Ingredient(options, n)
                }
                if (ingredients.isNotEmpty()) recipes += Recipe(parts[0][0], parts[1], count, ingredients)
            }
            return RecipeBook(recipes)
        }
    }
}
