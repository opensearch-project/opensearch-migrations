export const defaultContent: string = `function main(context) {
  return (document) => {
    // Your transformation logic here
    return document;
  };
}
// Entrypoint function
(() => main)();`;
