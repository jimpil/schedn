# schedn

An attempt to express (prismatic) schemas (and therefore validation configurations) in pure EDN intuitively & conveniently.

## Motivation
First of all Schema is great! It allows one to specify a 'validator' which looks, more or less, the same as the thing it will eventually be summoned to validate. This property is basically what separates Schema from the 'others' (for better or for worse is a completely separate discussion). So `schedn` takes the view that transparency is good and wants to keep it. So, why not write raw Schemas then? Well, let's consider an example:

```clj
(def Transaction {:cardholder {:last-name s/Str 
                               (s/optional-key :first-name) s/Str}
                  :amount {:value Double  
                           :currency-code s/Str}            
                  :card {(optional-key :pan) S/Str
                         (optional-key :expiry-date) S/Str}             
                  :token s/Str
                  })

```

OK, so what do we have here? First of all, this is NOT all data. It's actually a mixture of code + data. Secondly, there is quite a bit of clutter and room for error when writing deeply nested Schemas. In fact, the above Schema shown has a semantic error.  It marks all keys under :card as optional, but :card itself is mandatory! This means that in theory we would allow a completely empty :card entry! The same sort of thing could have happened for :cardholder entry. In general, when one writes a nested Schema by hand, he/she needs to manually figure out and keep track of which keys **cannot** be optional (because there is mandatory child somewhere downstream). There is clearly room for error in this exercise. Would it be great to be able to write something like this instead?
 
```clj
 (def Transaction {[[:cardholder :last-name] :mandatory] 'schema.core/Str
                   [[:cardholder :first-name] :optional] 'schema.core/Str
                   [[:amount :value] :mandatory] 'schema.core/Double
                   [[:amount :currency-code] :mandatory] 'schema.core/Str
                   [[:card :pan] :optional] 'schema.core/Str
                   [[:card :expiry-date] :optional] 'schema.core/Str
                   [[:token] :mandatory] 'schema.core/Str
                   })

```

We now have an opportunity to correctly generate all the keys prior to the leaf nodes (for which we have a presence indicator anyway). We've also uncluttered the whole thing without losing any structural information (the nesting points are clearly visible). I'd argue that this is more compact but yet, more readable and evident. Moreover, one can imagine trivial code to manipulate the above 'template' (e.g. inverting certain presence indicators or removing certain entries). 
  

## Terminology

`schedn` introduces 4 words to describe 4 very specific things:

1. By **schedn-template** we are going to refer to the a map full of schedn-entries
2. By **schedn-entry** we are going to refer to a tuple of `[ [path-to-leaf presence-indicator] leaf-validator ]`
3. By **leaf-validator** we are going to refer to a symbol which must resolve to a schema (to be used against a leaf value)
4. By **validation-configuration** we are going to refer to a map which, at the very least, contains a `:templates` key, which is a map whose values are all schedn-templates


For example, the following map is a **schema-template**:

```clj
{[[:a :b] :mandatory] 'my.awesome.schemas/UUID-str
 [[:d :e] :optional]  'my.awesome.schemas/PosDouble
 [[:k :j] :mandatory] 'my.awesome.schemas/RFC-3339-date-str}                     

```

## Usage

Usage is mainly centered around a single function `schedn.core/template->schema`. As the name suggests, you can call this on a schedn-template and you will get back a Schema. There are a couple of helpers to manipulate the template prior to producing the schema too (e.g. `remove-template-entries` & `override-template-statuses`). OK, so that's simple enough. Let's now see what more we can do.    
   
We mentioned earlier the notion of a 'validation-configuration'. This is basically a collection of templates. The reason for even using a validation-configuraiton over raw templates is to group the templates under a single entity, to facilitate the notion of **identification-comes-first**, and to offer an opportunity to refine (via s/constrained) the master schema that will be produced by merging all the templates. Let's see a full example:


```clj
{:classifier {:match ["request" "sale" "ssl"]
              :fragments ["request" [:a :b :c] [:a :c :y]]}
 :schema-constraints {:on-self ['schedn.core-test/d-AND-x]}
 :templates {:X {[[:a :b :c] :mandatory] s/Str
                 [[:a :b :d] :optional] s/Str}
            {:Y  [[:a :c :x] :conditional] s/Str
                 [[:a :c :y] :mandatory] s/Str }}}


```

Let's examine the `classifier` entry. The :match key corresponds to what the incoming message's (the message we're currently validating) identifier should be. The identifier is produced by extracting all the path-vectors found in :fragments from the incoming message. An optional prefix is supported as well (i.e. 'request' above). In other words, if the thing we're validating doesn't have value 'sale' under [:a :b :c] AND 'ssl' under [:a :c :x], identification will fail, and no further validation will occur. This feature is completely optional, and can be turned off by simply not supplying a :classifier entry.


The `schema-constraints` entry is a bit more involved. The constarints specified in its children apply to the top level schema as produce by merging all the templates and calling `template->schema` on it. It supports 2 keys and 2 keys only. These are **:on-self** & **:on-other** and they can coexist. They both have the same format but slightly different semantics. The format is essentially a vector of namespaced symbols. These symbols must resolve to functions which accept 1 argument if they are under `:on-self`, and 2 arguments if they are under `:on-other`. The master schema is refined sequentially for all resolved functions via `schema.core/constrained`. This feature is completely optional, and can be turned off by simply not supplying a :schema-constraints entry.


### on-self
This basically means that the refinements listed need access to the thing we're currently validating (hence they must expect 1 argument). 

### on-other
By the same token, this means  that the refinements listed need access to something external (e.g. some other map). Hence they must expect 2 arguments  - both self AND the-other.

## Caveats

## Limitations
Only for Clojure maps.

## License

Copyright © 2016 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
