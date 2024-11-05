/**
 * Logger that prints messages in the CDK ecosystem
 */
// eslint-disable-next-line @typescript-eslint/no-extraneous-class
export class CdkLogger {

    static info(message: string) {
      if (process.env.CDK_CLI_COMMAND === 'deploy') {
        console.log(message);
      }
    }

    static warn(message: string) {
      console.log(message);
    }

    static error(message: string) {
      console.log(message);
    }
}