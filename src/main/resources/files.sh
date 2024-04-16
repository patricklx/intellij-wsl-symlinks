while IFS='$\n' read line; do
  IFS=';';
  read -ra command <<< "$line";
  id=${command[0]};
  type=${command[1]};
  value=${command[2]};
  if [ "$type" == "is-symlink" ]; then
    (test -L "$value") && echo "$id;true" || echo "$id;false";
  fi
  if [ "$type" == "read-symlink" ]; then
    link=$(readlink -f "$value");
    echo "$id;$link";
  fi
done