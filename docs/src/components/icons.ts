// Each plugin's icon (src/assets/plugins/<id>.svg, from the brand repo), as a file URL.
const files = import.meta.glob<string>("../assets/plugins/*.svg", { eager: true, query: "?url", import: "default" });

export const iconOf = (id: string): string | undefined => files[`../assets/plugins/${id}.svg`];
