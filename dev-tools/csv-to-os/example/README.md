# NYC Taxi Data

A nice sample dataset is the NYC Taxi Data available and already cleaned up on Kaggle.
https://www.kaggle.com/competitions/nyc-taxi-trip-duration/data

This data requires a bit of pre-processing to best take advantage of OS. Specifically, the latitude and longitude fields need to be combined so that OS can interpret them as a geopoint. I also dropped the `store_and_fwd_flag` field because I wasn't interested in it.

The following code can be run on the dataset to prepare it in this way.

```
#!/usr/bin/env python
import pandas as pd

df = pd.read_csv('train.csv')
df['dropoff_location'] = df.apply(lambda x: '%s,%s' % (x['dropoff_latitude'], x['dropoff_longitude']), axis=1)
df['pickup_location'] = df.apply(lambda x: '%s,%s' % (x['pickup_latitude'], x['pickup_longitude']), axis=1)
df = df.drop(['pickup_longitude', 'pickup_latitude', 'dropoff_longitude', 'dropoff_latitude', 'store_and_fwd_flag'], axis=1)
df.to_csv('nyc-taxi-data.csv', index=False)
```

In this directory there's also the `index-settings` file that I used in creating the index. Additional shards probably aren't strictly necessary here, and some of the type mappings could likely be determined dynamically, but I went with directly specifying it all for the sake of education.

The csv-to-os script was invoked as follows: (`LBE` was my current load balancer endpoint to reach the cluster)
```
./csv_to_os.py nyc-taxi-data.csv --host "http://${LBE}" --port 80 --index taxi-data --index-settings nyc-taxi-settings.json
``` 
